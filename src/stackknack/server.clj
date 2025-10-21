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
  (:import (java.util UUID)
           (java.util.concurrent ThreadLocalRandom)))

;; -------------------------------
;; Session State
;; -------------------------------
(def sessions (atom {}))

;; -------------------------------
;; Rendering Helpers
;; -------------------------------

;; Load default C code from resource file if available
(def ^:private default-code
  (let [paths ["public/default.c" "default.c" "resources/public/default.c"]
        res (some io/resource paths)]
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

(defn- compute-frame-boundaries
  "Assign each stack cell to a frame based on frame addresses.
  Returns {:cell->frame {index frame} :frame-order [frame ...]}"
  [frames corrected-start-address stack-cell-count]
  (letfn [(decode-long [s]
            (when-let [value s]
              (try
                (Long/decode value)
                (catch NumberFormatException _ nil))))]
    (let [frames-with-addrs (->> frames
                                 (map (fn [frame]
                                        (when-let [addr (decode-long (get-in frame [:details :frame-address]))]
                                          {:frame frame :addr addr})))
                                 (remove nil?)
                                 (sort-by :addr >))
          cell->frame
          (if (and (seq frames-with-addrs) (pos? stack-cell-count))
            (loop [cell-idx 0
                   frame-idx 0
                   assignments {}
                   encountered []]
              (if (= cell-idx stack-cell-count)
                {:assignments assignments :order encountered}
                (let [cell-addr (- corrected-start-address (* cell-idx 8))
                      frame-idx (loop [idx frame-idx]
                                  (let [next-frame (nth frames-with-addrs (inc idx) nil)
                                        threshold (when-let [addr (:addr next-frame)]
                                                    (- addr 8))]
                                    (if (and next-frame threshold (<= cell-addr threshold))
                                      (recur (inc idx))
                                      idx)))
                      frame-entry (nth frames-with-addrs frame-idx (last frames-with-addrs))
                      frame (:frame frame-entry)
                      assignments (assoc assignments cell-idx frame)
                      encountered (if (= frame (last encountered))
                                    encountered
                                    (conj encountered frame))]
                  (recur (inc cell-idx) frame-idx assignments encountered))))
            {:assignments {} :order []})]
      {:cell->frame (:assignments cell->frame)
       :frame-order (:order cell->frame)})))

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
                                 (reduce merge {}))
          ;; Compute frame boundaries
          {:keys [cell->frame frame-order]} (compute-frame-boundaries frames corrected-start-address (count display-stack))
          rng (ThreadLocalRandom/current)
          color-default {:border "#7e8690" :label-bg "#e4e9ef" :text "#0f172a"}
          frame-colors (reduce (fn [acc frame]
                                 (let [idx (:index frame)
                                       hue (.nextInt rng 360)
                                       border (format "hsl(%d, 70%%, 45%%)" hue)
                                       label-bg (format "hsl(%d, 85%%, 90%%)" hue)]
                                   (assoc acc idx {:border border
                                                   :label-bg label-bg
                                                   :text "#0f172a"})))
                               {}
                               frame-order)
          cell-data (vec (for [i (range (count display-stack))]
                           (let [current-address (- corrected-start-address (* i 8))
                                 address-hex (format "0x%x" current-address)
                                 value (get display-stack i)
                                 register-label (get register-mappings address-hex)
                                 frame (get cell->frame i)]
                             {:idx i
                              :address address-hex
                              :value value
                              :register register-label
                              :frame frame
                              :frame-index (:index frame)})))
          frame-segments (->> cell-data (partition-by :frame-index) (remove empty?))]
      [:div.stack-content
       [:details.raw-json
        [:summary "Raw stack-data JSON"]
        [:pre (json/generate-string stack-data {:pretty true})]]

       ;; Registers section
       [:div.section
        [:h3 "Registers"]
        (if (seq registers)
          [:div.registers
           (for [[k v] (sort registers)]
             [:div.reg-item
              [:div.reg-name (name k)]
              [:div.reg-value v]])]
          [:div.frame-detail "No registers available"])]
       [:div.section
        [:h3 "Stack Memory"]
        (if (seq cell-data)
          (let [frame-boxes (for [segment frame-segments
                                   :let [{:keys [frame frame-index]} (first segment)
                                         colors (get frame-colors frame-index color-default)
                                         border-color (:border colors)
                                         label-bg (:label-bg colors)
                                         text-color (:text colors)
                                         frame-name (or (:function frame)
                                                        (str "Frame #" (or frame-index "?")))]]
                               [:div.frame-box
                                {:style {:border (str "2px solid " border-color)
                                         :border-radius "6px"
                                         :padding "6px"
                                         :background "var(--bg-primary)"
                                         :margin "6px 0"
                                         :box-shadow "0 1px 3px rgba(15,23,42,0.12)"}}
                                (when frame
                                  [:div.frame-label
                                   {:style {:background label-bg
                                            :color text-color
                                            :border (str "2px solid " border-color)
                                            :border-radius "4px"
                                            :padding "4px 8px"
                                            :font-weight 600
                                            :display "flex"
                                            :justify-content "space-between"
                                            :align-items "center"
                                            :margin-bottom "6px"}}
                                   [:span.frame-function frame-name]
                                   [:span.frame-index (str "#" (:index frame))]])
                                [:div.frame-cells
                                 (for [[seg-idx {:keys [address value register]}] (map-indexed vector segment)
                                       :let [total (count segment)
                                             first? (zero? seg-idx)
                                             last? (= seg-idx (dec total))
                                             cell-style (cond-> {:border (str "1px solid " border-color)
                                                                 :border-radius "0"
                                                                 :margin-bottom "3px"}
                                                               first? (assoc :border-top-left-radius "4px"
                                                                             :border-top-right-radius "4px")
                                                               last? (assoc :border-bottom-left-radius "4px"
                                                                            :border-bottom-right-radius "4px"
                                                                            :margin-bottom "0"))]]
                                   [:div.stack-cell {:style cell-style}
                                    [:div.address-label address]
                                    [:div.value-box value]
                                    (when register
                                      [:div.register-pointer {:data-register register} register])])]])]
            (into [:div.stack-visualization] frame-boxes)))]])
    [:div.placeholder "Compile and step through to see stack frames and registers"]))

(defn- home-page
  ([] (home-page nil nil nil nil nil nil))
  ([c-src asm-out msg session-id step-count stack-data]
   (let [code-changed? (and session-id
                           c-src
                           (not= c-src (get-in @sessions [session-id :c-code])))]
     (layout "StackKnack"
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

