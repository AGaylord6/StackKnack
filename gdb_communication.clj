#!/usr/bin/env -S clojure -M

(ns gdb-driver.core
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [me.raynes.conch.low-level :as conch])
  (:import (java.io BufferedReader InputStreamReader OutputStreamWriter BufferedWriter)))

(defn spawn-gdb
  "Start GDB as a subprocess on the given executable.
   Returns {:proc :in :out :err}."
  [exe-path]
  (let [proc (conch/proc "gdb" "--quiet" "--nx" "--args" exe-path)
        out-r (java.io.BufferedReader. (java.io.InputStreamReader. (:out proc)))
        err-r (java.io.BufferedReader. (java.io.InputStreamReader. (:err proc)))
        in-w  (java.io.BufferedWriter. (java.io.OutputStreamWriter. (:in proc)))]
    {:proc proc :in in-w :out out-r :err err-r}))

(defn send-cmd!
  "Send a GDB command (string) to stdin and flush."
  [gdb cmd]
  (doto (:in gdb)
    (.write (str cmd "\n"))
    (.flush)))

(defn read-output
  "Read available output lines until the GDB prompt '(gdb)' appears."
  [gdb]
  (let [out (:out gdb)
        sb (StringBuilder.)]
    (loop []
      (when (.ready out)
        (let [line (.readLine out)]
          (when line
            (.append sb line)
            (.append sb "\n"))
          (when-not (and line (str/includes? line "(gdb)"))
            (recur)))))
    (str sb)))

(defn gdb-setup!
  "Send standard setup commands to GDB."
  [gdb]
  (doseq [cmd ["set pagination off"
               "set confirm off"
               "set disassembly-flavor intel"
               "echo GDB ready.\\n"]]
    (send-cmd! gdb cmd))
  (Thread/sleep 300)
  (println (read-output gdb)))

(defn run-gdb-script
  "Run a fixed sequence of GDB commands, printing each response."
  [exe-path commands]
  (let [gdb (spawn-gdb exe-path)]
    (println "Starting GDB on:" exe-path)
    (gdb-setup! gdb)
    (doseq [cmd commands]
      (println "\n>>> " cmd)
      (send-cmd! gdb cmd)
      (Thread/sleep 200)
      (println (read-output gdb)))
    (println "\nAll commands executed. GDB session still alive.")
    (Thread/sleep 2000)
    (send-cmd! gdb "quit")
    (println "GDB terminated.")))

(defn -main [& args]
  (if (empty? args)
    (println "Usage: ./gdb_script.clj path/to/executable")
    (let [exe (first args)
          commands ["break main"
                    "run"
                    "info frame"
                    "backtrace"
                    "info locals"
                    "continue"]]
      (run-gdb-script exe commands))))

(apply -main *command-line-args*)
(System/exit 0)
