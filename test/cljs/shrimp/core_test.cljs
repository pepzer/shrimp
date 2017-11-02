;; Copyright © 2017 Giuseppe Zerbo. All rights reserved.
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns shrimp.core-test
  (:require [cljs.test :refer [deftest is]]
            [cljs.spec.alpha :as s]
            [shrimp.core :as sc :refer [put! take! alts!]]
            [shrimp.test :as st]
            [redlobster.promise :as p])
  (:use-macros [redlobster.macros :only [when-realised let-realised]]
               [shrimp.macros :only [defer defer-loop]]))

(deftest put-take
  (let [chan (sc/chan)
        value :foo
        put-prom (put! chan value)
        take-prom (take! chan)]
    (is (s/valid? :shrimp.core/chan chan) "put-take spec")
    (defer 2000 (do
                  (sc/try-realise take-prom nil)
                  (sc/try-realise put-prom nil)))
    (when-realised [put-prom take-prom]
      (is (= @put-prom true) "put-take put")
      (is (= @take-prom value) "put-take take")
      (when-realised [(sc/close! chan)]
        (is (sc/closed? chan) "put-take close")
        (is (s/valid? :shrimp.core/chan chan) "put-take spec #2")
        (st/done! 'put-take)))))

(deftest put-alts
  (let [chan1 (sc/chan 100 inc)
        chan2 (sc/chan 100 #(* -1 %))
        value (atom 0)
        alts-prom (alts! [chan1 chan2])
        put-prom (put! chan1 (swap! value inc))]

    (defer 2000 (do
                  (sc/try-realise alts-prom [nil nil])
                  (sc/try-realise put-prom nil)))

    (when-realised [put-prom alts-prom]
      (is (= @put-prom true) "put-alts put #1")
      (let [[v ch] @alts-prom]
        (is (= ch chan1) "put-alts chan #1")
        (is (= v 2) "put-alts value #1"))

      (is (s/valid? :shrimp.core/chan chan1) "put-alts spec #1")
      (is (s/valid? :shrimp.core/chan chan2) "put-alts spec #2")

      (let [alts-prom (alts! [chan1 chan2] 500 :foo)
            put-prom (put! chan2 (swap! value inc))]

        (defer 2000 (do
                      (sc/try-realise alts-prom [nil nil])
                      (sc/try-realise put-prom nil)))

        (when-realised [put-prom alts-prom]
          (is (= @put-prom true) "put-alts put #2")
          (let [[v ch] @alts-prom]
            (is (= ch chan2) "put-alts chan #2")
            (is (= v -2) "put-alts value #2"))

          (let [alts-prom (alts! [chan1 chan2] 500 :foo)]

            (defer 2000 (sc/try-realise alts-prom [nil nil]))

            (when-realised [alts-prom]
              (let [[v ch] @alts-prom]
                (is (= ch :expired) "put-alts chan #3")
                (is (= v :foo) "put-alts value #3"))

              (when-realised [(sc/close! chan1)
                              (sc/close! chan2)]
                (is (sc/closed? chan1) "put-alts close #1")
                (is (sc/closed? chan2) "put-alts close #2")

                (let-realised [alts-prom (alts! [chan1 chan2] 500 :foo)]
                  (let [[v ch] @alts-prom]
                    (is (= ch :dead) "put-alts chan dead")
                    (is (= v nil) "put-alts value nil"))
                  (st/done! 'put-alts))))))))))

(deftest take-loop-test
  (let [n 1000
        chan (sc/chan (* n 2))
        r (range n)]
    (doseq [i r]
      (put! chan i))
    (sc/close! chan)

    (is (s/valid? :shrimp.core/chan chan) "take-loop spec #1")

    (let-realised [end (defer-loop [acc []]
                          (if (sc/dead? chan)
                            acc
                            (let-realised [prom (take! chan)]
                              (defer-recur (conj acc @prom)))))]
      ;; end could contain a nil at the end
      (is (= r (take n @end)) "take-loop acc")
      (let-realised [prom (take! chan)]
        (is (= nil @prom) "take-loop after close!")
        (is (sc/dead? chan) "take-loop dead?")
        (is (s/valid? :shrimp.core/chan chan) "take-loop spec #2")
        (st/done! 'take-loop)))))
