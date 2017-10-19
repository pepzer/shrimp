;; Copyright © 2017 Giuseppe Zerbo. All rights reserved.
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns shrimp.macros)

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
  "Equivalent to loop but use setImmediate and defer-recur for recursion.

  Accept bindings and recur without consuming stack using setImmediate,
  needs a call to 'defer-recur' for recursion.
  Useful when using 'let-realised', 'when-realised' and similar inside the loop.
  "
  [bindings & forms]
  (if (odd? (count bindings))
    #?(:clj (throw (Exception. "Defer-loop needs an even number of binding clauses!"))
       :cljs (throw (js/Error. "Defer-loop needs an even number of binding clauses!")))
    (let [pairs (partition 2 bindings)
          symbols (mapv first pairs)
          args (map second pairs)
          fn-sym (gensym "defer-loop-")
          defer-recur (symbol "defer-recur")]
      `(do
         (defn- ~fn-sym
           ~symbols
           (let [t# (.-setImmediate js/global)
                 ~defer-recur (if t#
                                (fn [& args#]
                                  (js/setImmediate
                                   (fn [] (apply ~fn-sym args#))))
                                (fn [& args#]
                                  (js/setTimeout
                                   (fn [] (apply ~fn-sym args#))
                                   0)))]
             ~@forms))
         (~fn-sym ~@args)))))

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
