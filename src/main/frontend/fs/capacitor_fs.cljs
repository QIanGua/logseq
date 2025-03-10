(ns frontend.fs.capacitor-fs
  (:require ["@capacitor/filesystem" :refer [Encoding Filesystem]]
            [cljs-bean.core :as bean]
            [clojure.string :as string]
            [frontend.fs.protocol :as protocol]
            [frontend.mobile.util :as mobile-util]
            [frontend.util :as util]
            [lambdaisland.glogi :as log]
            [promesa.core :as p]
            [frontend.encrypt :as encrypt]
            [frontend.state :as state]
            [frontend.db :as db]))

(when (mobile-util/native-ios?)
  (defn iOS-ensure-documents!
    []
    (.ensureDocuments mobile-util/ios-file-container)))

(defn check-permission-android []
  (p/let [permission (.checkPermissions Filesystem)
          permission (-> permission
                         bean/->clj
                         :publicStorage)]
    (when-not (= permission "granted")
      (p/do!
       (.requestPermissions Filesystem)))))

(defn- clean-uri
  [uri]
  (when (string? uri)
    (util/url-decode uri)))

(defn readdir
  "readdir recursively"
  [path]
  (p/let [result (p/loop [result []
                          dirs [path]]
                   (if (empty? dirs)
                     result
                     (p/let [d (first dirs)
                             files (.readdir Filesystem (clj->js {:path d}))
                             files (-> files
                                       js->clj
                                       (get "files" []))
                             files (->> files
                                        (remove (fn [file]
                                                  (or (string/starts-with? file ".")
                                                      (and (mobile-util/native-android?)
                                                           (or (string/includes? file "#")
                                                               (string/includes? file "%")))
                                                      (= file "bak")))))
                             files (->> files
                                        (map (fn [file]
                                               (str (string/replace d #"/+$" "")
                                                    "/"
                                                    (if (mobile-util/native-ios?)
                                                      (util/url-encode file)
                                                      file)))))
                             files-with-stats (p/all
                                               (mapv
                                                (fn [file]
                                                  (p/chain
                                                   (.stat Filesystem (clj->js {:path file}))
                                                   #(js->clj % :keywordize-keys true)))
                                                files))
                             files-dir (->> files-with-stats
                                            (filterv
                                             (fn [{:keys [type]}]
                                               (contains? #{"directory" "NSFileTypeDirectory"} type)))
                                            (mapv :uri))
                             files-result
                             (p/all
                              (->> files-with-stats
                                   (filter
                                    (fn [{:keys [type]}]
                                      (contains? #{"file" "NSFileTypeRegular"} type)))
                                   (filter
                                    (fn [{:keys [uri]}]
                                      (some #(string/ends-with? uri %)
                                            [".md" ".markdown" ".org" ".edn" ".css"])))
                                   (mapv
                                    (fn [{:keys [uri] :as file-result}]
                                      (p/chain
                                       (.readFile Filesystem
                                                  (clj->js
                                                   {:path uri
                                                    :encoding (.-UTF8 Encoding)}))
                                       #(js->clj % :keywordize-keys true)
                                       :data
                                       #(assoc file-result :content %))))))]
                       (p/recur (concat result files-result)
                                (concat (rest dirs) files-dir)))))
          result (js->clj result :keywordize-keys true)]
    (map (fn [result] (update result :uri clean-uri)) result)))

(defn- contents-matched?
  [disk-content db-content]
  (when (and (string? disk-content) (string? db-content))
    (if (encrypt/encrypted-db? (state/get-current-repo))
      (p/let [decrypted-content (encrypt/decrypt disk-content)]
        (= (string/trim decrypted-content) (string/trim db-content)))
      (p/resolved (= (string/trim disk-content) (string/trim db-content))))))

(defn- write-file-impl!
  [_this repo _dir path content {:keys [ok-handler error-handler old-content skip-compare?]} stat]
  (if skip-compare?
    (p/catch
     (p/let [result (.writeFile Filesystem (clj->js {:path path
                                                     :data content
                                                     :encoding (.-UTF8 Encoding)
                                                     :recursive true}))]
       (when ok-handler
         (ok-handler repo path result)))
     (fn [error]
       (if error-handler
         (error-handler error)
         (log/error :write-file-failed error))))

    (p/let [disk-content (-> (p/chain (.readFile Filesystem (clj->js {:path path
                                                                      :encoding (.-UTF8 Encoding)}))
                                      #(js->clj % :keywordize-keys true)
                                      :data)
                             (p/catch (fn [error]
                                        (js/console.error error)
                                        nil)))
            disk-content (or disk-content "")
            ext (string/lower-case (util/get-file-ext path))
            db-content (or old-content (db/get-file repo (js/decodeURI path)) "")
            contents-matched? (contents-matched? disk-content db-content)
            pending-writes (state/get-write-chan-length)]
      (cond
        (and
         (not= stat :not-found)   ; file on the disk was deleted
         (not contents-matched?)
         (not (contains? #{"excalidraw" "edn" "css"} ext))
         (not (string/includes? path "/.recycle/"))
         (zero? pending-writes))
        (p/let [disk-content (encrypt/decrypt disk-content)]
          (state/pub-event! [:file/not-matched-from-disk path disk-content content]))

        :else
        (->
         (p/let [result (.writeFile Filesystem (clj->js {:path path
                                                         :data content
                                                         :encoding (.-UTF8 Encoding)
                                                         :recursive true}))]
           (p/let [content (if (encrypt/encrypted-db? (state/get-current-repo))
                             (encrypt/decrypt content)
                             content)]
             (db/set-file-content! repo (js/decodeURI path) content))
           (when ok-handler
             (ok-handler repo path result))
           result)
         (p/catch (fn [error]
                    (if error-handler
                      (error-handler error)
                      (log/error :write-file-failed error)))))))))

(defn get-file-path [dir path]
  (let [[dir path] (map #(some-> %
                                 js/decodeURI)
                        [dir path])
        dir (string/replace dir #"/+$" "")
        path (some-> path (string/replace #"^/+" ""))
        path (cond (nil? path)
                   dir

                   (string/starts-with? path dir)
                   path

                   :else
                   (str dir "/" path))]
    (if (mobile-util/native-ios?)
      (js/encodeURI (js/decodeURI path))
      path)))

(defrecord Capacitorfs []
  protocol/Fs
  (mkdir! [_this dir]
    (p/let [result (.mkdir Filesystem
                           (clj->js
                            {:path dir
                             ;; :directory (.-ExternalStorage Directory)
                             }))]
      (js/console.log result)
      result))
  (mkdir-recur! [_this dir]
    (p/let [result (.mkdir Filesystem
                           (clj->js
                            {:path dir
                             ;; :directory (.-ExternalStorage Directory)
                             :recursive true}))]
      (js/console.log result)
      result))
  (readdir [_this dir]                  ; recursive
    (readdir dir))
  (unlink! [_this _repo _path _opts]
    nil)
  (rmdir! [_this _dir]
    ;; Too dangerious!!! We'll never implement this.
    nil)
  (read-file [_this dir path _options]
    (let [path (get-file-path dir path)]
      (->
       (p/let [content (.readFile Filesystem
                                  (clj->js
                                   {:path path
                                    ;; :directory (.-ExternalStorage Directory)
                                    :encoding (.-UTF8 Encoding)}))
               content (-> (js->clj content :keywordize-keys true)
                           :data
                           clj->js)]
         content)
       (p/catch (fn [error]
                  (log/error :read-file-failed error))))))
  (delete-file! [_this repo dir path {:keys [ok-handler error-handler]}]
    (let [path (get-file-path dir path)]
      (p/catch
       (p/let [result (.deleteFile Filesystem
                                   (clj->js
                                    {:path path}))]
         (when ok-handler
           (ok-handler repo path result)))
       (fn [error]
         (if error-handler
           (error-handler error)
           (log/error :delete-file-failed error))))))
  (write-file! [this repo dir path content opts]
    (let [path (get-file-path dir path)]
      (p/let [stat (p/catch
                    (.stat Filesystem (clj->js {:path path}))
                    (fn [_e] :not-found))]
        (write-file-impl! this repo dir path content opts stat))))
  (rename! [_this _repo old-path new-path]
    (let [[old-path new-path] (map #(get-file-path "" %) [old-path new-path])]
      (p/catch
       (p/let [_ (.rename Filesystem
                          (clj->js
                           {:from old-path
                            :to new-path}))])
       (fn [error]
         (log/error :rename-file-failed error)))))
  (stat [_this dir path]
    (let [path (get-file-path dir path)]
      (p/let [result (.stat Filesystem (clj->js
                                        {:path path
                                         ;; :directory (.-ExternalStorage Directory)
                                         }))]
        result)))
  (open-dir [_this _ok-handler]
    (p/let [_    (when (= (mobile-util/platform) "android") (check-permission-android))
            path (p/chain
                  (.pickFolder mobile-util/folder-picker)
                  #(js->clj % :keywordize-keys true)
                  :path)
            _ (when (mobile-util/native-ios?) (mobile-util/sync-icloud-repo path))
            files (readdir path)
            files (js->clj files :keywordize-keys true)]
      (into [] (concat [{:path path}] files))))
  (get-files [_this path-or-handle _ok-handler]
    (readdir path-or-handle))
  (watch-dir! [_this dir]
    (p/do!
     (.unwatch mobile-util/fs-watcher)
     (.watch mobile-util/fs-watcher #js {:path dir}))))
