#!/usr/bin/env -S clojure -M

(ns gdb-driver.core
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [me.raynes.conch.low-level :as conch])
  (:import (java.io BufferedReader InputStreamReader OutputStreamWriter BufferedWriter)))

;; Are we recreating an x86 interpreter?
;; Bc we'll need to follow loops, jumps etc and track overall state

;; Instead of actual return address, just store/display name of caller func?

;; Instead of actual value of local vars, just trace size + labels?
;;     But loops wouldn't work then--dynamic allocation wouldn't be tracked
;;     Do loops affect stack frame?

;; Make up fake addresses of base pointer, stack pointer? Display label next to them

;; three options:
;; - Make our own interpreter to track registers/mem/rip
;; - Make dumb stack trace interpreter
;;     Functions in linear order, only register state at start/end of func, no local var values
;; - Use existing interpreter like gdb and parse its output

;; ----------------------------------------------------------------------------
;; Utilities for interacting with the gdb process
;; ----------------------------------------------------------------------------

(defn spawn-gdb
  "Spawn gdb for the given executable. Returns a map with :proc :in :out :err."
  [exe-path]
  (let [proc (conch/proc "gdb" "--quiet" "--nx" "--args" exe-path)
        out-r (java.io.BufferedReader. (java.io.InputStreamReader. (:out proc)))
        err-r (java.io.BufferedReader. (java.io.InputStreamReader. (:err proc)))
        in-w  (-> (:in proc) java.io.OutputStreamWriter. java.io.BufferedWriter.)]
    {:proc proc :in in-w :out out-r :err err-r}))


(defn flush-write! [^BufferedWriter w s]
  (.write w s)
  (.newLine w)
  (.flush w))

(defn read-until-prompt
  "Read lines from gdb output until we detect the gdb prompt '(gdb)'. Returns
   a string of the collected output (without the final prompt). This performs a blocking read."
  [^BufferedReader r]
  (let [sb (StringBuilder.)]
    (loop []
      (let [line (.readLine r)]
        (when (nil? line)
          (throw (ex-info "gdb closed the stream" {})))
        (let [trimmed line]
          ;; The gdb prompt often is '(gdb)' alone on a line or the next line after output.
          (if (str/includes? trimmed "(gdb)")
            (do
              ;; append any text before the prompt and return
              (when (not (empty? (str/trim (str/replace trimmed "(gdb)" ""))))
                (.append sb trimmed)
                (.append sb "\n"))
              (str sb))
            (do
              (.append sb line)
              (.append sb "\n")
              (recur))))))))

(defn gdb-send-cmd
  "Send a command to gdb and return its output (string)."
  [gdb-map cmd]
  (let [{:keys [in out err proc]} gdb-map]
    (flush-write! in cmd)
    ;; gdb writes to stdout; we wait for its prompt on stdout:
    (read-until-prompt out)))

;; ----------------------------------------------------------------------------
;; Parsing helpers (best-effort)
;; ----------------------------------------------------------------------------

(defn parse-registers
  "Parse 'info registers' output into a map of register -> value string (hex).
   This is a very simple extractor; it expects lines like 'rax 0x...' or 'rax 0x... 123'."
  [reg-output]
  (->> (str/split-lines reg-output)
       (map str/trim)
       (reduce (fn [m line]
                 (when (seq line)
                   (let [parts (str/split line #"\s+")
                         reg (first parts)
                         val (some #(when (re-find #"^0x[0-9a-fA-F]+" %) %) parts)]
                     (if val (assoc m reg val) m))))
               {})))

(defn extract-frames-from-bt
  "Return a vector of frame numbers from 'bt' output by scanning 'Frame <n>...' patterns
   or the typical gdb ' #0  func (args) at file:line' style. We'll return frame line strings."
  [bt-output]
  (->> (str/split-lines bt-output)
       (map str/trim)
       (remove empty?)
       vec))

(defn parse-info-frame
  "Try to extract CFA and saved regs from 'info frame' output. Returns a map with
   :raw (string) and optionally :cfa (hex string) and :saved-regs (map reg->addr)."
  [info-frame-output]
  (let [lines (str/split-lines info-frame-output)
        raw info-frame-output
        cfa (some (fn [ln]
                    (when-let [m (re-find #"(?:CFA|cfa|this frame's CFA|frame's CFA)[^\da-fA-Fx]*(0x[0-9a-fA-F]+)" (str/lower-case ln))]
                      ;; attempt to find 0x... anywhere
                      (second (re-find #"(0x[0-9a-fA-F]+)" ln))))
                  lines)
        ;; look for saved registers lines like "Saved registers:"
        saved (->> lines
                   (map #(.trim ^String %))
                   (filter #(or (re-find #"(saved.*register)" (str/lower-case %))
                                (re-find #"\b[r]?(bp|sp|ip|rip|rbp|rsp|rip)\b" %)))
                   (mapcat (fn [ln]
                             ;; try to find patterns: "rbp at 0x7fff..." or "rbp at offset -16"
                             (let [m1 (re-find #"(rbp|rsp|rip|rbx|rdi|rsi|rbp|rax|rbx|rcx|rdx|r8|r9|r10|r11|r12|r13|r14|r15)\s+at\s+(0x[0-9a-fA-F]+)" ln)
                                   m2 (re-find #"(rbp|rsp|rip|rbx|rdi|rsi|rax|rbx|rcx|rdx|r8|r9|r10|r11|r12|r13|r14|r15)\s+at\s+offset\s+(-?\d+)" (str/lower-case ln))]
                               (cond
                                 m1 [[(keyword (m1 1)) (m1 2)]]
                                 m2 [[(keyword (m2 1)) (Integer/parseInt (m2 2))]]
                                 :else []))))
                   (into {}))]
    {:raw raw :cfa cfa :saved-regs saved}))

;; ----------------------------------------------------------------------------
;; High level control loop
;; ----------------------------------------------------------------------------

(defn interactive-loop
  "Main interactive loop. gdb-map must be the map returned by spawn-gdb.
   It will drive the program, stepping and querying gdb as the user requests."
  [gdb-map]
  (println "Interactive GDB driver started.")
  (println "Controls: Enter = single-step (si), n = next (ni), r = run, b <addr> = breakpoint, q = quit")
  (loop []
    (print "\n(press Enter to step, or type command) > ")
    (flush)
    (let [input (or (read-line) "")]
      (cond
        (str/blank? input)
        (do
          ;; single instruction step
          (gdb-send-cmd gdb-map "si")
          (let [insn (gdb-send-cmd gdb-map "x/i $pc")
                regs-out (gdb-send-cmd gdb-map "info registers")
                bt (gdb-send-cmd gdb-map "bt full")]
            (println "\n--- Instruction ---")
            (println insn)
            (println "\n--- Registers ---")
            (println regs-out)
            (println "\n--- Backtrace ---")
            (println bt)
            ;; For each backtrace line, try to print info frame for frame numbers 0..N
            (let [frames (extract-frames-from-bt bt)]
              (doseq [idx (range (count frames))]
                (println (format "\n--- info frame %d ---" idx))
                (let [out (gdb-send-cmd gdb-map (str "info frame " idx))]
                  (println out)
                  (let [parsed (parse-info-frame out)]
                    (when-let [cfa (:cfa parsed)]
                      (println (format "  -> CFA detected: %s" cfa)))
                    (when-let [sr (:saved-regs parsed)]
                      (println "  -> Saved regs (best-effort parse):")
                      (doseq [[k v] sr]
                        (println (format "     %s -> %s" (name k) v)))))))))
          (recur))

        (= input "q")
        (do
          (println "Quitting and detaching gdb...")
          (gdb-send-cmd gdb-map "detach")
          (gdb-send-cmd gdb-map "quit")
          (System/exit 0))

        (= input "r")
        (do
          (println "Continuing (run)...")
          (gdb-send-cmd gdb-map "continue")
          (recur))

        (str/starts-with? input "b ")
        (let [addr (str/trim (subs input 2))]
          (println "Setting breakpoint at" addr)
          (gdb-send-cmd gdb-map (str "break " addr))
          (recur))

        (= input "n")
        (do
          (gdb-send-cmd gdb-map "ni")
          (let [insn (gdb-send-cmd gdb-map "x/i $pc")
                regs-out (gdb-send-cmd gdb-map "info registers")
                bt (gdb-send-cmd gdb-map "bt full")]
            (println "\n--- Instruction ---")
            (println insn)
            (println "\n--- Registers ---")
            (println regs-out)
            (println "\n--- Backtrace ---")
            (println bt)
            (recur)))

        :else
        (do
          ;; any other command pass to gdb
          (println "Sending raw command to gdb:" input)
          (let [out (gdb-send-cmd gdb-map input)]
            (println out))
          (recur))))))

;; ----------------------------------------------------------------------------
;; Startup: read args, spawn gdb, run initial commands
;; ----------------------------------------------------------------------------

(defn -main [& args]
  (if (< (count args) 1)
    (println "Usage: clojure -M gdb_driver.clj path/to/executable [args-for-program...]")
    (let [exe (first args)
          prog-args (drop 1 args)
          gdb-map (spawn-gdb exe)]
      (try
        ;; consume initial prompt
        (println "Eating initial gdb prompt...")
        (println (gdb-send-cmd gdb-map "set pagination off"))
        (println (gdb-send-cmd gdb-map "set confirm off"))
        ;; If program should be started with args, set them:
        (when (seq prog-args)
          (gdb-send-cmd gdb-map (str "set args " (str/join " " prog-args))))
        ;; start program but stop at entry:
        (println "Starting program under gdb (run -> will stop at main/entry)...")
        (println (gdb-send-cmd gdb-map "start"))
        ;; initial state printed for convenience
        (println (gdb-send-cmd gdb-map "x/i $pc"))
        (println (gdb-send-cmd gdb-map "info registers"))
        (interactive-loop gdb-map)
        (catch Exception e
          (println "Error:" (.getMessage e))
          (try
            (gdb-send-cmd gdb-map "quit")
            (catch Exception _))
          (System/exit 1))))))

;; Invoke main when script executed
;; (when (= *file* (-> *ns* ns-name str))
  ;; nothing; -main will be invoked when run with clojure -M
;;   )

(apply -main *command-line-args*)
(System/exit 0)
