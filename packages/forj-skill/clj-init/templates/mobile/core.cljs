(ns {{namespace}}.core
  "Entry point for {{project-name}} mobile app."
  (:require [expo.root :as expo-root]
            [re-frame.core :as rf]
            [reagent.core :as r]
            ["react-native" :as rn]
            ["expo-status-bar" :refer [StatusBar]]
            ["react-native-safe-area-context" :refer [SafeAreaProvider]]
            [{{namespace}}.views :as views]
            [{{namespace}}.events]
            [{{namespace}}.subs]))

;; =============================================================================
;; Root Component
;; =============================================================================

(defn root []
  [:> SafeAreaProvider
   [views/app]
   [:> StatusBar {:style "auto"}]])

;; =============================================================================
;; Lifecycle
;; =============================================================================

(defn start
  "Start/restart the app. Called on hot reload."
  {:dev/after-load true}
  []
  (expo-root/render-root (r/as-element [root])))

(defn init
  "Initialize the app. Called once on startup."
  []
  (rf/dispatch-sync [:app/initialize])
  (start))

(comment
  ;; REPL exploration
  (rf/dispatch [:app/initialize])
  @(rf/subscribe [:app/db])
  )
