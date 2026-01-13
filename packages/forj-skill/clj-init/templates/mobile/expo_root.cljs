(ns expo.root
  "Expo root component helper for shadow-cljs + React Native."
  (:require ["expo" :as expo]
            ["react" :as react]
            [reagent.core :as r]))

(defonce root-ref (atom nil))
(defonce root-component-ref (atom nil))

(defn render-root [root]
  (let [first-call? (nil? @root-ref)]
    (reset! root-component-ref root)
    (if first-call?
      (do
        (reset! root-ref
                (fn []
                  (let [forceUpdate (react/useReducer inc 0)]
                    (reset! root-ref forceUpdate)
                    @root-component-ref)))
        (expo/registerRootComponent (r/reactify-component @root-ref)))
      (@root-ref))))
