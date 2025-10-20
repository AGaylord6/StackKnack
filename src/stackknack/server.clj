(ns stackknack.server
  (:require
    [clojure.java.io :as io]
    [clojure.java.shell :as shell]
    [clojure.string :as str]
    [ring.middleware.resource :refer [wrap-resource]]
    [hiccup.page :as page]
    [ring.adapter.jetty :as jetty]
    [ring.middleware.params :refer [wrap-params]]
    [ring.middleware.multipart-params :refer [wrap-multipart-params]]
    [me.raynes.conch.low-level :as conch]
    [cheshire.core :as json]
    [gdb-manual :as gdb])
  (:import (java.util UUID)))

;; -------------------------------
;; Session State
;; -------------------------------
(def sessions (atom {}))

;; -------------------------------
;; Rendering Helpers
;; -------------------------------

;; Load default C code from resource file if available
(def ^:private default-code
  (let [res (io/resource "default.c")]
    (if res
      (slurp res)
      "#include <stdio.h>\n\nint add(int a, int b) {\n  return a + b;\n}\n\nint main() {\n  int x = add(2, 40);\n  printf(\"%d\\n\", x);\n  return 0;\n}\n")))

(defn- layout [title & body]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (page/html5
           [:head
            [:meta {:charset "utf-8"}]
            [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
            [:title title]
            (page/include-css "/style.css")]
           [:body body])})

(defn- render-stack-view [stack-data]
  (if stack-data
    (let [frames (:frames stack-data)
          ;; Find highest frame to know where to start
          highest-frame (when (seq frames) (apply max-key :index frames))
          start-address-str (get-in highest-frame [:details :frame-address])
          start-address (if start-address-str (Long/decode start-address-str) 0)
          stack-memory (:stack-memory stack-data)
          ;; Reverse stack-memory for display so top-of-stack appears first
          ;; Note: stack-memory[0] corresponds to (frame-address - 8), so the displayed first address should be start-address - 8.
          display-stack (vec (reverse stack-memory))
          ;; compute corrected start address (first element in stack-memory is start-address - 8)
          corrected-start-address (- start-address 8)
          registers (:registers stack-data)
          register-mappings (->> frames
                                 (map #(get-in % [:details :saved-register-mappings]))
                                 (reduce merge {}))]
      [:div.stack-visualization
       ;; Raw JSON dump for debugging / inspection
       [:details.raw-json
        [:summary "Raw stack-data JSON"]
        [:pre (json/generate-string stack-data {:pretty true})]]

       ;; Registers section
       [:div.section
        [:h3 "Registers"]
        (if (and registers (seq registers))
          [:div.registers
           (for [[k v] (sort registers)]
             [:div.reg-item
              [:div.reg-name (name k)]
              [:div.reg-value v]])]
          [:div.frame-detail "No registers available"]) ]

   (for [i (range (count display-stack))]
          ;; Iterate over stack contents and calculate addresses
     (let [current-address (- corrected-start-address (* i 8))
       address-hex (format "0x%x" current-address)
               value (get display-stack i)
               register-label (get register-mappings address-hex)]
           [:div.stack-cell
            [:div.address-label address-hex]
            [:div.value-box value]
            (when register-label
              [:div.register-pointer {:data-register register-label} register-label])]))])
    [:div.placeholder "Compile and step through to see stack frames and registers"]))

(defn- home-page
  ([] (home-page nil nil nil nil nil nil))
  ([c-src asm-out msg session-id step-count stack-data]
   (let [code-changed? (and session-id
                           c-src
                           (not= c-src (get-in @sessions [session-id :c-code])))]
     (layout "StackKnack — C→Assembly"
             [:header
              [:h1 "StackKnack"]
              [:p "Paste C on the left, get Intel syntax assembly on the right, and step through execution."]]
             [:main
              [:form {:method "POST" :action (if session-id "/step" "/compile") :id "main-form"}
               (when session-id
                 [:input {:type "hidden" :name "session-id" :value session-id}])
               [:div.cols
                [:div.col
                 [:label {:for "code"} "C Source Code"]
                 [:textarea {:name "code"
                            :id "code"
                            :wrap "off"
                            :spellcheck "false"
                            :placeholder "Enter your C code here..."
                            :autocomplete "off"
                            :autocorrect "off"
                            :autocapitalize "off"}
                  (or c-src default-code)]]
                [:div.col
                 [:label {:for "asm"} "Assembly Output (.s)"]
                 [:textarea {:id "asm"
                            :name "asm"
                            :readonly true
                            :wrap "off"
                            :placeholder "// Assembly output will appear here after compilation"}
                  (or asm-out "")]]
                [:div.col
                 [:label {:for "stack"} "Stack & Registers"]
                 [:div#stack-view.stack-view
                  (render-stack-view stack-data)]]]
               [:div.actions
                [:div.action-column
                 [:button {:type "submit"
                          :name "action"
                          :value "compile"
                          :id "compile-btn"}
                  "Compile to Assembly"]
                 (when msg
                   [:div.status-indicator
                    (if (str/includes? (str msg) "error")
                      [:span.error msg]
                      [:span msg])])]
                [:div.action-column
                 (when (and session-id asm-out (not code-changed?))
                   [:button {:type "submit"
                            :name "action"
                            :value "step"
                            :id "step-btn"
                            :class "step-btn"}
                    (str "Step Instruction (" (or step-count 0) ")")])
                 (when code-changed?
                   [:button {:type "submit"
                            :name "action"
                            :value "compile"
                            :class "step-btn disabled-step"
                            :disabled true}
                    "Recompile Required"])]
                [:div.action-column]]]]
              (when (and msg (str/includes? (str msg) "error"))
                [:p.msg.error msg])
             [:footer
              [:p [:strong "Tip:"] " x64 assembly is generated with 'gcc -S -fno-asynchronous-unwind-tables -fno-dwarf2-cfi-asm -fno-unwind-tables -g0 -masm=intel code.c -o assembly.s'"]]

             [:script "
               document.getElementById('main-form').addEventListener('submit', function(e) {
                 const action = document.activeElement.value;
                 if (action === 'compile') {
                   const compileBtn = document.getElementById('compile-btn');
                   if (compileBtn) { compileBtn.disabled = true; compileBtn.textContent = 'Compiling...'; }
                 } else if (action === 'step') {
                   const stepBtn = document.getElementById('step-btn');
                   if (stepBtn) { stepBtn.disabled = true; stepBtn.textContent = 'Stepping...'; }
                 }
               });

               document.addEventListener('keydown', function(e) {
                 if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') { e.preventDefault(); document.getElementById('compile-btn').click(); }
                 if ((e.metaKey || e.ctrlKey) && e.key === '.') { e.preventDefault(); const stepBtn = document.getElementById('step-btn'); if (stepBtn && !stepBtn.disabled) { stepBtn.click(); } }
               });
             "]))))

;; -------------------------------
;; File & Compilation Helpers
;; -------------------------------
(defn- write-temp-c! ^String [code]
  (let [dir (io/file (System/getProperty "java.io.tmpdir") "stackknack" (str (UUID/randomUUID)))]
    (.mkdirs dir)
    (let [cfile (io/file dir "main.c")]
      (spit cfile code)
      (.getAbsolutePath cfile))))

(defn- run-stack-script! [^String c-path]
  (let [script "./assembler.clj"
        {:keys [in out err] :as p} (conch/proc script c-path)
        out-str (slurp (:out p))
        err-str (slurp (:err p))]
    (conch/exit-code p)
    {:stdout out-str :stderr err-str}))

(defn- compile-to-executable! [^String c-path]
  (let [base (str/replace c-path #"\.c$" "")
        exe-path base
        result (shell/sh "gcc" "-g" c-path "-o" exe-path)]
    (if (zero? (:exit result)) exe-path nil)))

(defn- c-to-asm [^String code]
  (let [limit 50000]
    (cond
      (or (nil? code) (str/blank? (str code)))
      {:error "No C source provided. Please enter some code to compile."}

      (> (count code) limit)
      {:error (format "C source too large (>%d characters). Please reduce the code size." limit)}

      :else
      (try
        (let [c-path (write-temp-c! code)
              base   (str/replace c-path #"\.c$" "")
              s-path (str base ".s")
              {:keys [stdout stderr]} (run-stack-script! c-path)]
          (if (.exists (io/file s-path))
            (let [exe-path (compile-to-executable! c-path)
                  session-id (str (UUID/randomUUID))]
              (when exe-path
                (swap! sessions assoc session-id {:exe-path exe-path
                                                   :step-count 0
                                                   :c-code code}))
              {:asm (slurp s-path)
               :log stdout
               :session-id session-id})
            {:error (str "Compilation failed:\n\n" (or stderr stdout))}))
        (catch Exception e
          {:error (str "Server error: " (.getMessage e))})))))

;; -------------------------------
;; Step Execution (Direct GDB call)
;; -------------------------------
(defn- step-execution [session-id]
  "Step one instruction and return state as JSON."
  (if-let [session (get @sessions session-id)]
    (let [new-step-count (inc (:step-count session))
          raw-output (gdb/run-gdb-to-step (:exe-path session) new-step-count)
          result (if raw-output
                   (gdb/parse-gdb-output raw-output)
                   {:error "GDB returned no output"})]
      (if (:error result)
        result
        (do
          (swap! sessions assoc-in [session-id :step-count] new-step-count)
          (assoc result :step-count new-step-count))))
    {:error "Invalid session"}))

;; -------------------------------
;; Request Handler
;; -------------------------------
(defn handler [req]
  (case [(:request-method req) (:uri req)]
    [:get "/"] (home-page)

    [:post "/compile"]
    (let [code (get-in req [:params "code"])
          {:keys [asm log error session-id]} (c-to-asm code)]
      (if error
        (home-page code nil error nil nil nil)
        (home-page code asm "Compiled successfully!" session-id 0 nil)))

    [:post "/step"]
    (let [session-id (get-in req [:params "session-id"])
          code (get-in req [:params "code"])
          asm (get-in req [:params "asm"])
          action (get-in req [:params "action"])]
      (if (= action "compile")
        (let [{:keys [asm log error session-id]} (c-to-asm code)]
          (if error
            (home-page code nil error nil nil nil)
            (home-page code asm "Compiled successfully!" session-id 0 nil)))
        (let [result (step-execution session-id)]
          (home-page code asm
                     (:error result)
                     session-id
                     (get-in @sessions [session-id :step-count])
                     result))))

    ;; 404
    (layout "404 - Not Found"
            [:div {:style "text-align:center;padding:4rem;"}
             [:h1 "404"]
             [:p "Page not found"]
             [:a {:href "/"} "← Back to Home"]])))

(def app
  (-> handler
      (wrap-resource "public")
      wrap-multipart-params
      wrap-params))

;; -------------------------------
;; Server Entry Point
;; -------------------------------
(defn -main
  [& [mode]]
  (let [port (or (some-> (System/getenv "PORT") Integer/parseInt) 3000)
        host (case (some-> mode str/lower-case)
               "public" "0.0.0.0"
               "local" "127.0.0.1"
               nil     "127.0.0.1"
               "127.0.0.1")]
    (println (format "StackKnack server running on http://%s:%d" host port))
    (jetty/run-jetty app {:host host :port port :join? true})))

