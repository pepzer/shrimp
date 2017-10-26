;; Copyright © 2017 Giuseppe Zerbo. All rights reserved.
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns shrimp.test
  (:require [shrimp.core :as sp]
            [cljs.test :as t]
            [redlobster.promise :as p])
  (:use-macros [redlobster.macros :only [let-realised]]
               [shrimp.macros :only [defer]]))

(defonce ^:private one-test-data (atom nil))
(defonce ^:private all-tests-data (atom nil))
(defonce ^:private test-ch (atom nil))
(defonce tests-done (atom nil))

(defn init! []
  (reset! all-tests-data
          {:tests 0 :pass 0
           :fail 0 :error 0})
  (reset! test-ch (sp/chan)))

(defn inc-report-counter! [orig-inc-report!]
  (fn [name]
    (sp/put! @test-ch name)
    (orig-inc-report! name)))

(defmethod cljs.test/report [:cljs.test/default :summary] [m]
  (let [tot (:test m)]
    (swap! one-test-data assoc :tot tot)))

(defn- print-results! [data]
  (let [{:keys [tests fail error pass]} data]
    (println "\nRan" tests "tests containing"
             (+ pass fail error) "assertions.")
    (println fail "failures," error "errors.")))

(defn reset-collection! []
  (reset! tests-done (p/promise))
  (swap! all-tests-data #(merge-with + % @one-test-data))
  (reset! one-test-data
          {:tests 0 :pass 0
           :fail 0 :error 0}))

(defn close! [print-all?]
  (when print-all?
    (println "\nAll namespaces:")
    (print-results! (swap! all-tests-data
                           #(merge-with + % @one-test-data))))
  (sp/close! @test-ch))

(defn- test-complete! []
  (let [curr (-> one-test-data
                 (swap! update :tests inc)
                 :tests)
        tot (:tot @one-test-data)]
    (when (and tot (= curr tot))
      (print-results! @one-test-data)
      (p/realise @tests-done true))))

(defn collect-results! []
  (let-realised [prom (sp/take! @test-ch)]
    (if @prom
      (do
        (case @prom
          :done (test-complete!)
          :pass (swap! one-test-data update :pass inc)
          :error (swap! one-test-data update :error inc)
          :fail (swap! one-test-data update :fail inc))
        (defer 50 (collect-results!)))
      nil)))

(defn done!
  ([] (done! nil))
  ([test-id]
   (when test-id
     (println "Completing" test-id))
   (sp/put! @test-ch :done)))
