(ns expo.root
  (:require [reagent.core :as r]
            ["expo-status-bar" :refer [StatusBar]]
            ["react-native" :as rn]
            ["react-native-safe-area-context" :refer [SafeAreaProvider]]))

;; Root state holds the current app component
(defonce root-state (r/atom nil))

(defn reload-root [app-component]
  (reset! root-state app-component))

(defn App []
  [:> SafeAreaProvider
   [:> StatusBar {:style "auto"}]
   (when-let [app @root-state]
     [app])])

;; Export for index.js
(def ^:export App (r/reactify-component App))
