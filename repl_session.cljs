(require '[shrimp.core :as sc])

(def chan1 (sc/chan))

(sc/close! chan1)

(sc/closed? chan1)

(sc/dead? chan1)

(use-macros '[redlobster.macros :only [let-realised when-realised]])

                                        ; Define the channel
(def chan1 (sc/chan))

                                        ; Try to take from the channel
                                        ; Print the value when the promise is realised
(let-realised [prom (sc/take! chan1)]
  (do (println "Val: " @prom)
      (sc/close! chan1)))

                                        ; Put a value inside the channnel
(sc/put! chan1 "foo")

(sc/close! chan1)

;; => Val: foo


                                        ; Define the channels
(def chan1 (sc/chan))
(def chan2 (sc/chan))

                                        ; Try to take from both channels
                                        ; Print the value when the promise is realised
(let-realised [prom (sc/alts! [chan1 chan2])]
  (let [[v ch] @prom]
    (if (= ch chan1)
      (println "Val: " v ", from chan1")
      (println "Val: " v ", from chan2"))
    (sc/close! chan1)
    (sc/close! chan2)))

                                        ; Put a value inside the channnel
(sc/put! chan1 "foo")

;; => Val: foo , from chan1

(use-macros '[shrimp.macros :only [defer-loop defer realise-time defer-time]])


                                        ; Define the channel
(def chan1 (sc/chan))

                                        ; The loop stops when the take! promise realises to nil
(defer-loop [prom (sc/take! chan1) :delay 100]
  (when-realised [prom]
    (if @prom
      (do
        (println "Val:" @prom ", from defer-loop")

                                        ; defer-recur works like recur
                                        ; It is only defined under the scope of the defer-loop macro
        (defer-recur (sc/take! chan1)))

      (println "Exit from the loop"))))

                                        ; Put a value inside the channnel
(sc/put! chan1 "foo")
(sc/put! chan1 "bar")

(sc/close! chan1)

;; => Val: foo , from defer-loop
;; => Val: bar , from defer-loop
;; => Exit from the loop

(defer 2000 (println "foo"))

;; => foo

(realise-time
 (defer-loop [x 0 :delay 100]
   (if (< x 10)
     (defer-recur (inc x))
     (println "x:" x))))

;; => #object[redlobster.promise.Promise]
;; => x: 10
;; => "Elapsed time: 1006.799804 msecs"

(defer-time
  (defer 2000 (do (println "foo")
                  (do-time (println "bar")))))

;; => foo
;; => bar
;; => "Elapsed time: 2003.601113 msecs"
