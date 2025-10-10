(ns stackknack.server
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [ring.middleware.resource :refer [wrap-resource]]
    [hiccup.page :as page]
    [ring.adapter.jetty :as jetty]
    [ring.middleware.params :refer [wrap-params]]
    [ring.middleware.multipart-params :refer [wrap-multipart-params]]
    [me.raynes.conch.low-level :as conch])
  (:import
    (java.util UUID)))

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
  ([] (home-page nil nil nil))
  ([c-src asm-out msg]
   (layout "StackKnack — C→Assembly"
           [:header
            [:h1 "StackKnack"]
            [:p "Paste C on the left, get Intel syntax assembly on the right."]]
           [:main
            [:form {:method "POST" :action "/compile"}
             [:div.cols
              [:div.col
               [:label {:for "code"} "C source"]
               [:textarea {:name "code" :id "code" :rows 24 :wrap "off" :spellcheck "false"}
                (or c-src "#include <stdio.h>\n\nint add(int a,int b){return a+b;}\n\nint main(){\n  int x = add(2, 40);\n  printf(\"%d\\n\", x);\n  return 0;\n}\n")]]
              [:div.col
               [:label {:for "asm"} "Assembly (.s)"]
               [:textarea {:id "asm" :rows 24 :readonly true :wrap "off"}
                (or asm-out "// assembly output will appear here")]]]
             [:div.actions
              [:button {:type "submit"} "Compile to Assembly"]]]
            (when msg [:p.msg msg])]
           [:footer
            [:p "Tip: We call your existing ./stack.clj under the hood."]])))

(defn- write-temp-c! ^String [code]
  (let [dir (io/file (System/getProperty "java.io.tmpdir") "stackknack" (str (UUID/randomUUID)))]
    (.mkdirs dir)
    (let [cfile (io/file dir "main.c")]
      (spit cfile code)
      (.getAbsolutePath cfile))))

(defn- run-stack-script! [^String c-path]
  ;; Run  existing stack.clj as a child process (so its System/exit doesn't kill the server)
  (let [script "./stack.clj"
        {:keys [in out err] :as p} (conch/proc script c-path)
        out-str (slurp (:out p))
        err-str (slurp (:err p))]
    (conch/exit-code p)
    {:stdout out-str :stderr err-str}))

(defn- c-to-asm [^String code]
  (let [limit 20000]
    (cond
      (or (nil? code) (str/blank? (str code)))
      {:error "No C source provided."}

      (> (count code) limit)
      {:error (format "C source too large (>%d chars)." limit)}

      :else
      (try
        (let [c-path (write-temp-c! code)
              base   (str/replace c-path #"\.c$" "")
              s-path (str base ".s")
              {:keys [stdout stderr]} (run-stack-script! c-path)]
          (if (.exists (io/file s-path))
            {:asm (slurp s-path) :log stdout}
            {:error (str "Compilation failed.\n" (or stderr stdout))}))
        (catch Exception e
          {:error (str "Server error: " (.getMessage e))})))))

(defn handler [req]
  (case [(:request-method req) (:uri req)]
    [:get "/"] (home-page)
    [:post "/compile"]
    (let [code (get-in req [:params "code"])
          {:keys [asm log error]} (c-to-asm code)]
      (if error
        (home-page code nil error)
        (home-page code asm (when (seq log) "Compiled successfully."))))
    (layout "Not found" [:h1 "404"] [:p "Not found"])))

(def app
  (-> handler
        (wrap-resource "public")
      wrap-multipart-params
      wrap-params))

(defn -main [& _]
  (let [port (or (some-> (System/getenv "PORT") Integer/parseInt) 3000)]
    (println (format "StackKnack server on http://localhost:%d" port))
    (jetty/run-jetty app {:port port :join? true})))
