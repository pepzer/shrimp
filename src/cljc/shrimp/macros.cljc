;; Copyright © 2017 Giuseppe Zerbo. All rights reserved.
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns shrimp.macros
  #?(:cljs (:require [redlobster.promise]))
  #?(:cljs (:use-macros [redlobster.macros :only [let-realised]])))

(def ^:private r-promise 'redlobster.promise/promise)
(def ^:private realise 'redlobster.promise/realise)
(def ^:private let-realised 'redlobster.macros/let-realised)

(defmacro defer
  "Run the given forms in the next tick of the event loop or after a delay.

  If the delay is negative run the form with setImmediate, otherwise run the forms
  with setTimeout and the delay.

  :param delay
    Delay in milliseconds to defer the forms, or negative to run with setImmediate.
  :param forms
    Forms to execute through setTimeout or setImmediate.
  "
  [delay & forms]
  `(let [fn# (fn [] ~@forms)
         t# (.-setImmediate js/global)]
     (if (and (< ~delay 0) t#)
       (js/setImmediate fn#)
       (js/setTimeout fn# (max ~delay 0)))))

(defmacro defer-loop
  "Imitate loop but allow async blocks returning promises inside the loop.

  When `defer-recur` is called recur with `js/setImmediate` or `js/setTimeout`.
  When [`:delay` value] is in the bindings recur with `js/setTimeout` and value.
  Useful when using `let-realised`, `when-realised` and similar inside the loop.
  "
  [bindings & forms]
  (if (odd? (count bindings))
    #?(:clj (throw (Exception. "defer-loop: odd number of args in bindings!"))
       :cljs (throw (js/Error. "defer-loop: odd number of args in bindings!")))
    (let [special? #{:delay}
          dispatch (fn [[symbols args specials] [sym value]]
                     (if (special? sym)
                       [symbols args (assoc specials sym value)]
                       [(conj symbols sym) (conj args value) specials]))
          [symbols args specials] (reduce dispatch [[] [] {}]
                                          (partition 2 bindings))
          delay (:delay specials)
          recur-args (gensym "recur-args-")
          tram-fn (gensym "tram-fn-")
          defer-form (or (and delay
                              (int? delay)
                              (pos? delay)
                              `(js/setTimeout (fn []
                                                (~tram-fn ~tram-fn ~recur-args))
                                              ~delay))
                         `(js/setImmediate (fn []
                                             (~tram-fn ~tram-fn ~recur-args))))]
      `(let [return-prom# (~r-promise)
             defer-recur# (fn [& args#]
                            {:defer-recur-args args#})
             body-fn# (fn ~symbols
                        (let [~(symbol "defer-recur") defer-recur#]
                          (~r-promise (do ~@forms))))
             trampoline# (fn [tram-fn# recur-args#]
                           (~let-realised [cycle-prom# (apply body-fn#
                                                              recur-args#)]
                            (if (and (map? (deref cycle-prom#))
                                     (contains? (deref cycle-prom#)
                                                :defer-recur-args))
                              (let [~recur-args (:defer-recur-args
                                                 (deref cycle-prom#))
                                    ~tram-fn tram-fn#]
                                ~defer-form)
                              (~realise return-prom# (deref cycle-prom#)))))]
         (js/setImmediate (fn []
                            (trampoline# trampoline# ~args)))
         return-prom#))))

(defmacro defer-time
  "Async version of cljs.core/time, run expr and return its value.

  To actually compute the elapsed time invoke '(do-time last-expr)' as your last
  expression inside a possibly async block, the result of last-expr is returned.
  Invoke '(do-time)' with no arguments as your last expression to return nil.
  Calling 'do-time' at any point (even multiple times) is legit although probably
  not too useful.
  "
  [expr]
  `(let [start# (system-time)
         do-time# (fn [& res#]
                    (prn (cljs.core/str "Elapsed time: "
                                        (.toFixed (- (system-time) start#) 6)
                                        " msecs"))
                    (first res#))
         ~(symbol "do-time") do-time#]
     ~expr))
