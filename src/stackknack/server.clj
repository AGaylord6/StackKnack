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
    [cheshire.core :as json])
  (:import
    (java.util UUID)))

;; Store session state for debugging
(def sessions (atom {}))

(defn- render-register [reg val]
  [:div.reg-item
   [:span.reg-name (name reg)]
   [:span.reg-value val]])

(defn- render-registers [registers]
  (when (seq registers)
    [:div.section
     [:h3 "Registers"]
     [:div.registers
      (for [[reg val] registers]
        (render-register reg val))]]))

(defn- render-frame [frame]
  [:div.frame
   [:div.frame-header
    (str "#" (:index frame) " " (:function frame))
    (when (and (:file frame) (:line frame))
      [:span.frame-loc (str " at " (:file frame) ":" (:line frame))])]
   (when (seq (:args frame))
     [:div.frame-args
      [:strong "Args: "]
      (str/join ", " (map #(str (:name %) "=" (:value %)) (:args frame)))])
   (when-let [frame-addr (get-in frame [:details :frameAddress])]
     [:div.frame-detail (str "Frame: " frame-addr)])])

(defn- render-frames [frames frame-count]
  (when (seq frames)
    [:div.section
     [:h3 (str "Stack Frames (" frame-count ")")]
     (for [frame frames]
       (render-frame frame))]))

(defn- render-stack-view [stack-data]
  (if stack-data
    [:div.stack-content
     (render-registers (:registers stack-data))
     (render-frames (:frames stack-data) (:frameCount stack-data))]
    [:div.placeholder "Compile and step through to see stack frames and registers"]))

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
                  (or c-src "#include <stdio.h>\n\nint add(int a, int b) {\n  return a + b;\n}\n\nint main() {\n  int x = add(2, 40);\n  printf(\"%d\\n\", x);\n  return 0;\n}\n")]]
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
              [:p [:strong "Tip:"] " We call your existing ./assembler.clj under the hood to generate Intel syntax assembly."]]
             
             ;; Minimal client-side JavaScript for form handling
             [:script "
               document.getElementById('main-form').addEventListener('submit', function(e) {
                 const action = document.activeElement.value;
                 if (action === 'compile') {
                   const compileBtn = document.getElementById('compile-btn');
                   if (compileBtn) {
                     compileBtn.disabled = true;
                     compileBtn.textContent = 'Compiling...';
                   }
                 } else if (action === 'step') {
                   const stepBtn = document.getElementById('step-btn');
                   if (stepBtn) {
                     stepBtn.disabled = true;
                     stepBtn.textContent = 'Stepping...';
                   }
                 }
               });
               
               // Keyboard shortcuts
               document.addEventListener('keydown', function(e) {
                 if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
                   e.preventDefault();
                   document.getElementById('compile-btn').click();
                 }
                 if ((e.metaKey || e.ctrlKey) && e.key === '.') {
                   e.preventDefault();
                   const stepBtn = document.getElementById('step-btn');
                   if (stepBtn && !stepBtn.disabled) {
                     stepBtn.click();
                   }
                 }
               });
             "]))))

(defn- write-temp-c! ^String [code]
  (let [dir (io/file (System/getProperty "java.io.tmpdir") "stackknack" (str (UUID/randomUUID)))]
    (.mkdirs dir)
    (let [cfile (io/file dir "main.c")]
      (spit cfile code)
      (.getAbsolutePath cfile))))

(defn- run-stack-script! [^String c-path]
  ;; Run existing assembler.clj as a child process
  (let [script "./assembler.clj"
        {:keys [in out err] :as p} (conch/proc script c-path)
        out-str (slurp (:out p))
        err-str (slurp (:err p))]
    (conch/exit-code p)
    {:stdout out-str :stderr err-str}))

(defn- compile-to-executable! [^String c-path]
  "Compile C to executable for debugging"
  (let [base (str/replace c-path #"\.c$" "")
        exe-path base
        result (shell/sh "gcc" "-g" c-path "-o" exe-path)]
    (if (zero? (:exit result))
      exe-path
      nil)))

(defn- run-gdb-step! [^String exe-path steps]
  "Run gdb_manual.clj with executable and step count"
  (let [script "src/gdb_manual.clj"
        result (shell/sh "clojure" "-M" script exe-path (str steps))]
    (if (zero? (:exit result))
      (try
        (json/parse-string (:out result) true)
        (catch Exception e
          {:error (str "Failed to parse GDB output: " (.getMessage e))}))
      {:error (str "GDB execution failed: " (:err result))})))

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

(defn- step-execution [session-id]
  "Step one instruction and return new state"
  (if-let [session (get @sessions session-id)]
    (let [new-step-count (inc (:step-count session))
          gdb-result (run-gdb-step! (:exe-path session) new-step-count)]
      (if (:error gdb-result)
        gdb-result
        (do
          (swap! sessions assoc-in [session-id :step-count] new-step-count)
          (assoc gdb-result :step-count new-step-count))))
    {:error "Invalid session"}))

(defn handler [req]
  (case [(:request-method req) (:uri req)]
    [:get "/"] 
    (home-page)
    
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
        ;; Recompile
        (let [{:keys [asm log error session-id]} (c-to-asm code)]
          (if error
            (home-page code nil error nil nil nil)
            (home-page code asm "Compiled successfully!" session-id 0 nil)))
        ;; Step
        (let [result (step-execution session-id)]
          (if (:error result)
            (home-page code asm (:error result) session-id 
                      (get-in @sessions [session-id :step-count]) nil)
            (home-page code asm nil session-id 
                      (:step-count result) result)))))
    
    (layout "404 - Not Found" 
            [:div {:style "text-align: center; padding: 4rem;"}
             [:h1 "404"]
             [:p "Page not found"]
             [:a {:href "/" :style "color: var(--accent-blue);"} "← Back to Home"]])))

(def app
  (-> handler
      (wrap-resource "public")
      wrap-multipart-params
      wrap-params))

(defn -main [& _]
  (let [port (or (some-> (System/getenv "PORT") Integer/parseInt) 3000)]
    (println (format "StackKnack server running on http://localhost:%d" port))
    (jetty/run-jetty app {:port port :join? true})))
