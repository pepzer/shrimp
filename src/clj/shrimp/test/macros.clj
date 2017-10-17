;; Copyright © 2017 Giuseppe Zerbo. All rights reserved.
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns shrimp.test.macros)

(def r-when-realised 'redlobster.macros/when-realised)
(def t-init! 'shrimp.test/init!)
(def t-close! 'shrimp.test/close!)
(def t-reset! 'shrimp.test/reset-collection!)
(def t-tests-done 'shrimp.test/tests-done)
(def t-collect 'shrimp.test/collect-results!)
(def t-report-counter! 'shrimp.test/inc-report-counter!)
(def t-orig-inc-report 'cljs.test/inc-report-counter!)

(defn- realise-form [in-form ns]
  `(do
     (~t-reset!)
     (cljs.test/run-tests (quote ~ns))
     (~t-collect)
     (~r-when-realised [(deref ~t-tests-done)]
      ~in-form)))

(defn- realise-forms [forms end-form]
  (reduce realise-form
          end-form
          (reverse forms)))

(defmacro run-async-tests
  [& forms]
  (let [orig-rep-fn (gensym "orig-fn")
        end-form `(do (~t-close!)
                      (set! ~t-orig-inc-report ~orig-rep-fn))
        chained (realise-forms forms end-form)]
    `(let [~orig-rep-fn ~t-orig-inc-report]
       (set! ~t-orig-inc-report
             (~t-report-counter! ~orig-rep-fn))
       (~t-init!)
       ~chained)))
