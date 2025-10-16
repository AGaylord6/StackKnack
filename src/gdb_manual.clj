#!/usr/bin/env -S clojure -M

(ns gdb-manual
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [clojure.java.shell :as shell]
            [clojure.java.io :as io]))

;; -------------------------------
;; Parsing Functions
;; -------------------------------

(defn parse-bt-line [line]
  "Parse a single backtrace line."
  (let [;; Handle both formats: "#0  main (...)" and "#0  0x4005dc in main (...)"
        [_ index func args-str file line-num]
        (or (re-matches #"#(\d+)\s+(\S+)\s*\((.*?)\)\s*(?:at\s+(\S+):(\d+))?" line)
            (re-matches #"#(\d+)\s+0x[0-9a-fA-F]+\s+in\s+(\S+)\s*\((.*?)\)\s*(?:at\s+(\S+):(\d+))?" line))
        args (when args-str
               (map (fn [a]
                      (let [[k v] (str/split a #"=" 2)]
                        {:name (str/trim k)
                         :value (str/trim v)}))
                    (remove str/blank? (str/split args-str #","))))]
    (when index
      {:index (Integer/parseInt index)
       :function func
       :args args
       :file file
       :line (when line-num (Integer/parseInt line-num))})))

(defn parse-bt [text]
  "Parse backtrace output into frame data."
  (->> (str/split-lines text)
       (filter #(re-matches #"#\d+.*" %))
       (map parse-bt-line)
       (filter identity)
       (into [])))

(defn parse-frame [text]
  "Parse info frame output for detailed frame information."
  (let [lines (str/split-lines text)
        frame-at-line (some #(re-find #"(?:Stack )?frame at (0x[0-9a-fA-F]+)" %) lines)
        rip-line (some #(re-find #"rip = (0x[0-9a-fA-F]+) in (\S+) \(([^:]+):(\d+)\)" %) lines)
        saved-regs-section (drop-while #(not (str/includes? % "Saved registers:")) lines)
        saved-regs (when (seq saved-regs-section)
                     (->> (rest saved-regs-section)
                          (take-while #(str/starts-with? % "  "))
                          (mapcat (fn [line]
                                    (re-seq #"(\w+) at (0x[0-9a-fA-F]+)" line)))
                          (map (fn [[_ reg addr]] [addr reg]))
                          (into {})))]
    (cond-> {}
      frame-at-line (assoc :frame-address (nth frame-at-line 1))
      rip-line (assoc :rip (nth rip-line 1)
                      :function (nth rip-line 2)
                      :file (nth rip-line 3)
                      :line (when (nth rip-line 4) (Integer/parseInt (nth rip-line 4))))
      saved-regs (assoc :saved-register-mappings saved-regs)
      :always (assoc :raw text))))

(defn parse-registers [text]
  "Parse register dump output."
  (->> (str/split-lines text)
       (keep (fn [line]
               (when-let [[_ reg val] (re-matches #"(\S+)\s+([0-9xa-fA-F]+).*" line)]
                 [(keyword reg) val])))
       (into {})))

(defn parse-stack-memory [text]
  "Parse stack memory dump output into an array of 8-byte words (strings like 0x...)."
  (let [lines (str/split-lines text)
        ;; Match gdb memory dump lines like:
        ;; 0x7fffffffd1d0: 0x00007fffffffd1e0 0x00000000004005f0 ...
        memory-lines (filter #(re-matches #"^\s*0x[0-9a-fA-F]+:.*" %) lines)]
    (->> memory-lines
         (mapcat (fn [line]
                   ;; Extract all 0x... tokens on the line
                   (let [hex-tokens (re-seq #"0x[0-9a-fA-F]+" line)]
                     ;; First token is the line address (drop it), keep the rest (the memory words)
                     (rest hex-tokens))))
         (map #(let [s (subs % 2) ; drop "0x"
                     ;; normalize to 16 hex digits (64-bit) for consistent output
                     padded (format "%016x" (Long/parseLong s 16))]
                 (str "0x" padded)))
         (into []))))

;; -------------------------------
;; State Management
;; -------------------------------

(def ^:dynamic *step-count* 0)
(def ^:dynamic *registers* {})

(defn generate-initial-gdb-script [current-steps]
  "Generate initial GDB script to get backtrace and registers only."
  (let [setup-commands ["set pagination off"
                        "set confirm off"
                        "set disassembly-flavor intel"
                        "break main"
                        "run"]
        step-commands (repeat current-steps "si")
        info-commands ["bt"
                       "info registers"
                       "info registers rsp"
                       "quit"]]
    (str/join "\n" (concat setup-commands step-commands info-commands))))

(defn generate-frame-info-script [current-steps frame-indices]
  "Generate GDB script to get frame info for specific frames."
  (let [setup-commands ["set pagination off"
                        "set confirm off"
                        "set disassembly-flavor intel"
                        "break main"
                        "run"]
        step-commands (repeat current-steps "si")
        frame-commands (map #(str "info frame " %) frame-indices)
        cleanup-commands ["quit"]]
    (str/join "\n" (concat setup-commands step-commands frame-commands cleanup-commands))))

(defn extract-stack-addresses [output]
  "Extract stack addresses from frame info to calculate stack memory range."
  (let [lines (str/split-lines output)
        ;; Find RSP (current stack pointer)
        rsp-line (some #(re-find #"rsp\s+0x([0-9a-fA-F]+)" %) lines)
        current-rsp (when rsp-line (str "0x" (nth rsp-line 1)))

        ;; Find main's frame address by looking for the frame that contains "main"
        ;; First find main's frame index from backtrace
        main-frame-index (->> lines
                              (filter #(re-matches #"#\d+.*" %))
                              (map #(re-matches #"#(\d+).*main.*" %))
                              (filter identity)
                              first
                              second)
        ;; Then look for the detailed frame info sections and find the one at main's index
        frame-sections (filter #(str/starts-with? % "Stack frame at") lines)
        ;; For now, get the highest address frame (main should be at highest address)
        all-frame-addrs (->> lines
                             (keep #(re-find #"Stack frame at (0x[0-9a-fA-F]+)" %))
                             (map #(nth % 1))
                             (map #(Long/parseLong (subs % 2) 16))
                             (sort >)) ; Sort descending
        main-frame-addr (when (seq all-frame-addrs)
                          (format "0x%x" (first all-frame-addrs)))]
    {:current-rsp current-rsp
     :main-frame-addr main-frame-addr}))(defn calculate-stack-size [rsp-addr main-frame-addr]
  "Calculate number of bytes between current RSP and main's frame address."
  (when (and rsp-addr main-frame-addr)
    (let [rsp-val (Long/parseLong (subs rsp-addr 2) 16)
          main-val (Long/parseLong (subs main-frame-addr 2) 16)]
      ;; Stack grows downward: main frame is at highest address, RSP gets lower as stack grows
      (if (> main-val rsp-val)
        (- main-val rsp-val)
        256)))) ; Default size if calculation seems wrong

(defn generate-stack-dump-script [current-steps stack-info]
  "Generate GDB script to dump stack memory from RSP to main's frame address."
  (let [setup-commands ["set pagination off"
                        "set confirm off"
                        "set disassembly-flavor intel"
                        "break main"
                        "run"]
        step-commands (repeat current-steps "si")
        ;; Calculate exact bytes from RSP to main's frame address (stack bottom)
        stack-size (if (and (:current-rsp stack-info) (:main-frame-addr stack-info))
                     (calculate-stack-size (:current-rsp stack-info) (:main-frame-addr stack-info))
                     256) ; Fallback
        ;; Allow larger stack dumps to capture full frames
        capped-size (min stack-size 2048)
        word-count (max 1 (quot (+ capped-size 7) 8)) ; number of 8-byte words
        ;; Use 'gx' to print 8-byte (64-bit) words directly
        dump-command [(str "x/" word-count "gx $rsp")]
        cleanup-commands ["quit"]]
    (str/join "\n" (concat setup-commands step-commands dump-command cleanup-commands))))

(defn get-stack-memory [exe-path steps combined-output]
  "Get stack memory dump based on frame information."
  (let [stack-info (extract-stack-addresses combined-output)
        stack-script (generate-stack-dump-script steps stack-info)
        stack-script-file (java.io.File/createTempFile "gdb-stack" ".txt")]
    (try
      (spit stack-script-file stack-script)
      (let [stack-result (shell/sh "gdb" "--batch" "--nx" "--command" (.getAbsolutePath stack-script-file) exe-path)]
        (io/delete-file stack-script-file true)
        (if (= (:exit stack-result) 0)
          (:out stack-result)
          (do
            (println "GDB Stack Dump Error:" (:err stack-result))
            ""))) ; Return empty string on error
      (catch Exception e
        (io/delete-file stack-script-file true)
        (println "Stack Dump Exception:" (.getMessage e))
        ""))))

(defn run-gdb-to-step [exe-path steps]
  "Run GDB up to a specific number of steps and return state."
  ;; First pass: get backtrace and registers
  (let [initial-script (generate-initial-gdb-script steps)
        initial-script-file (java.io.File/createTempFile "gdb-initial" ".txt")]
    (try
      (spit initial-script-file initial-script)
      (let [initial-result (shell/sh "gdb" "--batch" "--nx" "--command" (.getAbsolutePath initial-script-file) exe-path)]
        (io/delete-file initial-script-file true)
        (if (= (:exit initial-result) 0)
          (let [initial-output (:out initial-result)
                ;; Parse backtrace to count frames
                bt-lines (filter #(re-matches #"#\d+.*" %) (str/split-lines initial-output))
                frame-count (count bt-lines)
                frame-indices (range frame-count)]

            (if (> frame-count 0)
              ;; Second pass: get frame details for existing frames only
              (let [frame-script (generate-frame-info-script steps frame-indices)
                    frame-script-file (java.io.File/createTempFile "gdb-frames" ".txt")]
                (try
                  (spit frame-script-file frame-script)
                  (let [frame-result (shell/sh "gdb" "--batch" "--nx" "--command" (.getAbsolutePath frame-script-file) exe-path)]
                    (io/delete-file frame-script-file true)
                    (if (= (:exit frame-result) 0)
                      ;; Third pass: get stack memory dump
                      (let [combined-output (str initial-output "\n" (:out frame-result))
                            stack-output (get-stack-memory exe-path steps combined-output)]
                        (str combined-output "\n" stack-output))
                      (do
                        (println "GDB Frame Info Error:" (:err frame-result))
                        initial-output))) ; Return initial output even if frame info fails
                  (catch Exception e
                    (io/delete-file frame-script-file true)
                    (println "Frame Info Exception:" (.getMessage e))
                    initial-output)))
              ;; No frames, just return initial output
              initial-output))
          (do
            (println "GDB Error for " steps " steps:" (:err initial-result))
            nil)))
      (catch Exception e
        (io/delete-file initial-script-file true)
        (println "Exception:" (.getMessage e))
        nil))))

(defn parse-gdb-output [output]
  "Parse GDB output and return state without registers in display."
  (let [lines (str/split-lines output)

        ;; Parse backtrace
        bt-lines (filter #(re-matches #"#\d+.*" %) lines)
        bt-text (str/join "\n" bt-lines)
        frames (parse-bt bt-text)

        ;; Parse registers (store but don't display)
        reg-lines (filter #(re-matches #"[a-z0-9]+\s+0x[0-9a-fA-F]+.*" %) lines)
        reg-text (str/join "\n" reg-lines)
        registers (parse-registers reg-text)

        ;; Parse stack memory dump
        stack-memory (parse-stack-memory output)

        ;; Parse frame details - extract individual "Stack frame at" sections (but exclude stack memory dump)
        clean-output-for-frames (->> (str/split-lines output)
                                     (take-while #(not (re-matches #"0x[0-9a-fA-F]+:[\s\t]+.*0x[0-9a-fA-F]+.*" %)))
                                     (str/join "\n"))
        frame-sections (re-seq #"Stack frame at[^\n]*(?:\n[^\n]*)*?(?=\nStack frame at|\n$|$)" clean-output-for-frames)
        detailed-frames
        (map-indexed (fn [idx frame]
                       (let [frame-text (if (< idx (count frame-sections))
                                          (nth frame-sections idx)
                                          "")
                             frame-details (if (seq frame-text)
                                            (parse-frame frame-text)
                                            {:raw ""})]
                         (assoc frame :details frame-details)))
                     frames)]

    ;; Store registers globally but don't include in returned state
    (alter-var-root #'*registers* (constantly registers))

    {:frame-count (count frames)
     :frames detailed-frames
     :stack-memory stack-memory}))

;; -------------------------------
;; Interactive Interface
;; -------------------------------

(defn print-stack-state [state step-num]
  "Print the current stack state."
  (println (str "\n=== Step " step-num " ==="))
  (println (json/generate-string state {:pretty true})))

(defn interactive-loop [exe-path]
  "Run the interactive stepping loop."
  (println (str "Interactive GDB Stepper for: " exe-path))
  (println "Commands:")
  (println "  si    - Step one assembly instruction")
  (println "  quit  - Exit")
  (println "  help  - Show help")

  ;; Show initial state (step 0)
  (let [initial-output (run-gdb-to-step exe-path 0)]
    (when initial-output
      (let [initial-state (parse-gdb-output initial-output)]
        (print-stack-state initial-state 0))))

  (loop [step-count 0]
    (print "\ngdb> ")
    (flush)
    (let [input (str/trim (read-line))]
      (cond
        (= input "quit")
        (println "Goodbye!")

        (= input "help")
        (do
          (println "Commands:")
          (println "  si    - Step one assembly instruction")
          (println "  quit  - Exit")
          (println "  help  - Show help")
          (recur step-count))

        (= input "si")
        (let [new-step-count (inc step-count)
              output (run-gdb-to-step exe-path new-step-count)]
          (if output
            (let [state (parse-gdb-output output)]
              (print-stack-state state new-step-count)
              (recur new-step-count))
            (do
              (println "Error executing step or program ended.")
              (recur step-count))))

        (str/blank? input)
        (recur step-count)

        :else
        (do
          (println (str "Unknown command: " input ". Type 'help' for available commands."))
          (recur step-count))))))

(defn -main [& args]
  (cond
    (< (count args) 1)
    (println "Usage: clj -M gdb_interactive_simple.clj <executable-path>")

    :else
    (let [exe-path (first args)]
      (if (.exists (java.io.File. exe-path))
        (interactive-loop exe-path)
        (println (str "Error: Executable not found: " exe-path))))))

;; Auto-run main when script is invoked
(apply -main *command-line-args*)
