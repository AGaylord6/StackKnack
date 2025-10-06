#!/usr/bin/env clojure

;; stack.clj
;; Usage: ./stack.clj path/to/file.c

(ns compile-to-asm
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]))

(println "Running clojure script")

(defn -main [& args]
  (if (empty? args)
    (do
      (println "Usage: stack.clj <source.c>")
      (System/exit 1))

    (let [source-file (first args)
          thing (println "Compiling:" source-file)
          ;; Replace .c with .s for assembly output
          asm-file (str (str/replace source-file #"\.c$" "") ".s")
          ;; Run gcc with -S to produce assembly
          result (shell/sh "gcc" "-S" "-masm=intel" source-file "-o" asm-file)]

      (if (zero? (:exit result))
        (println "Assembly generated at:" asm-file)
        (do
          (println "Error compiling:" (:err result))
          (System/exit (:exit result)))))))

(apply -main *command-line-args*)
;; if we don't exit here, clojure enters repl mode and hangs
(System/exit 0)