(ns parse-gdb
  (:require [clojure.string :as str]
            [clojure.java.io :as io]))

;; ----------------------------------------------------------------------------
;; 1. Fake GDB output loader
;; ----------------------------------------------------------------------------

(defn read-file [path]
  "Read a file and return lines as a vector of strings."
  (when (.exists (io/file path))
    (-> path
        io/file
        slurp
        str/split-lines)))

(defn fake-gdb-output [step]
  "Load fake GDB output from folder test_gdb_output/<step>/"
  (let [folder (str "test_gdb_output/" step)]
    {:pc            (read-file (str folder "/pc.txt"))
     :registers     (read-file (str folder "/info_registers.txt"))
     :frames        (read-file (str folder "/info_frame.txt"))
     :backtrace     (read-file (str folder "/bt_full.txt"))}))

;; ----------------------------------------------------------------------------
;; 2. Parsers
;; ----------------------------------------------------------------------------

(defn parse-registers [lines]
  "Parse info_registers.txt into map of :register -> value"
  (->> (map str/trim lines)
       (remove str/blank?)
       (map #(str/split % #"\s+"))
       (reduce (fn [m [reg val & _]]
                 (assoc m (keyword reg) val))
               {})))

(defn parse-stack [lines]
  "Parse bt_full.txt into vector of frames:
   Each frame is a map {:level n :func name :file file :line line :locals {var val}}"
  (let [lines (map str/trim lines)]
    (loop [remaining lines
           frames []]
      (if (empty? remaining)
        frames
        (let [line (first remaining)]
          (if-let [[_ lvl func args file line-num]
                   (re-find #"#([0-9]+)\s+(?:0x[0-9a-fA-F]+\s+in\s+)?([_A-Za-z0-9<>~]+)\s*\(([^)]*)\)\s+at\s+([^\s:]+):([0-9]+)" line)]
            ;; Found a new frame
            (let [[locals rest-lines] 
                  (split-with #(re-matches #"\s+.+=" %) (rest remaining))
                  locals-map (->> locals
                                  (map #(str/trim %))
                                  (map #(str/split % #"=" 2))
                                  (map (fn [[k v]] [(keyword (str/trim k)) (str/trim v)]))
                                  (into {}))]
              (recur rest-lines
                     (conj frames {:level (Integer/parseInt lvl)
                                   :func func
                                   :args args
                                   :file file
                                   :line (Integer/parseInt line-num)
                                   :locals locals-map})))
            ;; Not a frame line, skip
            (recur (rest remaining) frames)))))))

;; ----------------------------------------------------------------------------
;; 3. Optional: parse frames (info frame)
;; ----------------------------------------------------------------------------

(defn parse-info-frame [lines]
  "Best-effort parser for info_frame.txt to extract CFA and saved registers"
  {:raw lines}) ;; placeholder; can extend later

;; ----------------------------------------------------------------------------
;; 4. Interactive loop
;; ----------------------------------------------------------------------------

(defonce debugger-state
  (atom {:step 0
         :registers {}
         :stack []
         :pc nil
         :frames {}}))

(defn step! []
  "Advance one step and update debugger state using fake GDB output"
  (let [step (:step @debugger-state)
        gdb-out (fake-gdb-output step)
        regs (parse-registers (:registers gdb-out))
        stack (parse-stack (:backtrace gdb-out))
        frames (parse-info-frame (:frames gdb-out))]
    (swap! debugger-state assoc
           :step (inc step)
           :registers regs
           :stack stack
           :pc (:pc gdb-out)
           :frames frames)
    ;; print state for convenience
    (println "\n=== STEP" step "===")
    (println "PC:" (:pc gdb-out))
    (println "Registers:" regs)
    (println "Stack:" (map #(select-keys % [:level :func :file :line]) stack))
    (println "Frames:" frames)
    (when-not (seq (:pc gdb-out))
      (println "End of fake program."))))

(defn run-demo []
  (println "Starting fake GDB debugger. Press Enter to step.")
  (loop []
    (when (< (:step @debugger-state) 4) ;; adjust max steps as needed
      (read-line)
      (step!)
      (recur))))

(defn -main [& args]
  (run-demo))
