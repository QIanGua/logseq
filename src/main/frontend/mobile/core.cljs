(ns frontend.mobile.core
  (:require [frontend.mobile.util :as mobile-util]
            [frontend.state :as state]
            ["@capacitor/app" :refer [^js App]]
            ["@capacitor/keyboard" :refer [^js Keyboard]]
            #_:clj-kondo/ignore
            ["@capacitor/status-bar" :refer [^js StatusBar]]
            [clojure.string :as string]
            [frontend.fs.capacitor-fs :as fs]
            [frontend.components.repo :as repo]
            [frontend.handler.web.nfs :as nfs-handler]
            [frontend.handler.editor :as editor-handler]
            [frontend.handler.notification :as notification]
            [promesa.core :as p]
            [frontend.util :as util]
            [frontend.config :as config]))

(defn- ios-init
  []
  (let [path (fs/iOS-ensure-documents!)]
    (println "iOS container path: " path))

  ;; Keyboard watcher
  ;; (.addListener Keyboard "keyboardWillShow"
  ;;               #(state/pub-event! [:mobile/keyboard-will-show]))
  (.addListener Keyboard "keyboardDidShow"
                #(state/pub-event! [:mobile/keyboard-did-show])))

(defn init!
  []
  ;; patch back navigation
  (when (mobile-util/native-android?)
    (.addListener App "backButton"
                  #(let [href js/window.location.href]
                     (when (true? (cond
                                    (state/get-left-sidebar-open?)
                                    (state/set-left-sidebar-open! false)

                                    (state/settings-open?)
                                    (state/close-settings!)

                                    (state/modal-opened?)
                                    (state/close-modal!)

                                    :else true))

                       (if (or (string/ends-with? href "#/")
                               (string/ends-with? href "/")
                               (not (string/includes? href "#/")))
                         (.exitApp App)
                         (js/window.history.back))))))

  (when (mobile-util/native-ios?)
    (ios-init))

  (when (mobile-util/is-native-platform?)
    (.addListener mobile-util/fs-watcher "watcher"
                  (fn [event]
                    (state/pub-event! [:file-watcher/changed event])))

    (.addEventListener js/window "statusTap"
                       #(util/scroll-to-top true))

    (.addListener App "appStateChange"
                  (fn [^js state]
                    (when-let [repo (state/get-current-repo)]
                      (let [is-active? (.-isActive state)
                            repo-dir (config/get-repo-dir repo)]
                        (if is-active?
                          (p/do!
                           (when (mobile-util/native-ios?)
                             (mobile-util/sync-icloud-repo repo-dir))
                           (nfs-handler/refresh! repo repo/refresh-cb)
                           (notification/show! "Notes updated!" :success true))
                          (editor-handler/save-current-block!))))))))
