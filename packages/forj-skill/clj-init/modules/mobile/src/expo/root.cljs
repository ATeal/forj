(ns expo.root
  (:require [reagent.core :as r]
            ["expo-status-bar" :refer [StatusBar]]
            ["react-native-safe-area-context" :refer [SafeAreaProvider]]
            [mobile.core :as core]
            [mobile.views :as views]))

;; Root state holds the current app component
(defonce root-state (r/atom nil))

(defn reload-root [app-component]
  (reset! root-state app-component))

;; Main app wrapper with SafeArea and StatusBar
(defn app-root []
  [:> SafeAreaProvider
   [:> StatusBar {:style "auto"}]
   (when-let [app @root-state]
     [app])])

;; Initialize the app
(defn init []
  (core/init)
  (reload-root views/app))

;; Export for index.js - reactify the root component
(def ^:export App (r/reactify-component app-root))
