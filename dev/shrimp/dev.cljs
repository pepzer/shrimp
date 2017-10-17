(ns shrimp.dev
  (:require [shrimp.core :as core]
            [figwheel.client :as fw]))

(defn -main []
  (fw/start { }))

(set! *main-cli-fn* -main)
