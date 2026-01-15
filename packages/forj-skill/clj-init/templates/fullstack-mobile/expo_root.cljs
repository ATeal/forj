(ns expo.root
  "Expo root component helper for shadow-cljs + React Native.
   Compatible with React 19's stricter hooks rules."
  (:require ["expo" :as expo]
            [reagent.core :as r]))

(defonce root-component (r/atom nil))
(defonce registered? (atom false))

(defn- wrapper []
  (fn []
    (when-let [root @root-component]
      [root])))

(defn render-root
  "Register and render the root component.
   Call with a Reagent component (not as-element)."
  [root]
  (reset! root-component root)
  (when-not @registered?
    (reset! registered? true)
    (expo/registerRootComponent (r/reactify-component (wrapper)))))
