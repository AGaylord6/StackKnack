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
          ;; -g adds debug info
          result (shell/sh "gcc" "-S" "-masm=intel" source-file "-o" asm-file)]

          ;; TODO: create 1 version with debugging info (cfi_ directives) and one without for displaying clean asm

      (if (zero? (:exit result))
        (println "Assembly generated at:" asm-file)
        (do
          (println "Error compiling:" (:err result))
          (System/exit (:exit result)))))))

(apply -main *command-line-args*)
;; if we don't exit here, clojure enters repl mode and hangs
(System/exit 0)

;; TODO: as assembly is stepped through, construct stack frames
;; Use dwarf/cfi (call frame info) unwind directives to help
;; .cfi_startproc tells us a new frame starts
;; .cfi_def_cfa_offset tells us how much space to allocate on the stack for local variables
;;      cfa: canonical frame address, reference point for current stack frame based on rsp (or other register)
;; .cfi_offset tells us where saved registers are stored on the stack with reference to rsp
;; .cfi_endproc tells us the frame ends