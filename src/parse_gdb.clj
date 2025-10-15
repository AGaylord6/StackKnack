(ns parse-gdb
  (:require [clojure.string :as str]
            [cheshire.core :as json]))

;; -------------------------------
;; Helper Functions
;; -------------------------------

(defn parse-bt-line [line]
  ;; Example: "#0  main (argc=1, argv=0x7fffffffe2f8) at main.c:5"
  (let [[_ index func args-str file line-num]
        (re-matches #"#(\d+)\s+(\S+)\s*\((.*?)\)\s*(?:at\s+(\S+):(\d+))?" line)
        args (when args-str
               (map (fn [a]
                      (let [[k v] (str/split a #"=" 2)]
                        {:name (str/trim k)
                         :value (str/trim v)}))
                    (remove str/blank? (str/split args-str #","))))]
    {:index (Integer/parseInt index)
     :function func
     :args args
     :file file
     :line (when line-num (Integer/parseInt line-num))}))

(defn parse-bt [text]
  (->> (str/split-lines text)
       (filter #(re-matches #"#\d+.*" %))
       (map parse-bt-line)
       (into [])))

(defn parse-frame [text]
  ;; Parses output of "info frame n"
  ;; Example key lines:
  ;; Stack level 0, frame at 0x7fffffffe1f0:
  ;; rip = 0x55555555467d in main (main.c:5); saved rip = 0x7ffff7a05290
  ;; Saved registers:
  ;;   rbp at 0x7fffffffe1e0, rip at 0x7fffffffe1e8
  (let [lines (str/split-lines text)
        ;; Extract frame starting address
        frame-at-line (some #(re-find #"frame at (0x[0-9a-fA-F]+)" %) lines)
        ;; Extract current rip info
        rip-line (some #(re-find #"rip = (0x[0-9a-fA-F]+) in (\S+) \(([^:]+):(\d+)\)" %) lines)
        ;; Parse saved registers to create address-to-label mappings
        saved-regs-section (drop-while #(not (str/includes? % "Saved registers:")) lines)
        saved-regs (when (seq saved-regs-section)
                     (->> (rest saved-regs-section)
                          (take-while #(str/starts-with? % "  "))
                          (mapcat (fn [line]
                                    ;; Extract all "register at address" pairs from the line
                                    (re-seq #"(\w+) at (0x[0-9a-fA-F]+)" line)))
                          (map (fn [[_ reg addr]] [addr reg]))
                          (into {})))]
    (cond-> {}
      frame-at-line (assoc :frame-address (nth frame-at-line 1))
      rip-line (assoc :rip (nth rip-line 1)
                      :function (nth rip-line 2)
                      :file (nth rip-line 3)
                      :line (Integer/parseInt (nth rip-line 4)))
      saved-regs (assoc :saved-register-mappings saved-regs)
      :always (assoc :raw text))))

(defn parse-registers [text]
  ;; Example line: "rax            0x0      0"
  (->> (str/split-lines text)
       (keep (fn [line]
               (when-let [[_ reg val] (re-matches #"(\S+)\s+([0-9xa-fA-F]+).*" line)]
                 [(keyword reg) val])))
       (into {})))

;; -------------------------------
;; Main orchestrator
;; -------------------------------

(defn parse-gdb-output [base-path]
  (let [bt-text (slurp (str base-path "/bt.txt"))
        frames (parse-bt bt-text)
        frame-details
        (map (fn [f]
               (let [n (:index f)
                     frame-file (str base-path "/info_frame_" n ".txt")
                     frame-info (parse-frame (slurp frame-file))]
                 (assoc f :details frame-info)))
             frames)
        regs (parse-registers (slurp (str base-path "/registers.txt")))]
    {:frames frame-details
     :registers regs}))

(defn -main [& args]
  (let [data (parse-gdb-output "test_gdb_output")]
    (println (json/generate-string data {:pretty true}))))

