;; Copyright © 2017 Giuseppe Zerbo. All rights reserved.
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns shrimp.core
  (:require [redlobster.promise :as p]
            [clojure.spec.alpha :as s])
  (:use-macros [redlobster.macros :only [when-realised let-realised]]
               [shrimp.macros :only [defer]]))

(declare chan-loop)

(defn- try-realise
  "Realise a promise only if not already realised.

  Avoid throwing an error if the promise is realised more than once.

  :param prom
    The promise to realise.
  :param v
    The value to send to the promise.
  "
  [prom v]
  (when-not (p/realised? prom)
    (p/realise prom v)))

(defn- try-realise-error
  "Realise a failed promise only if not already realised.

  Avoid throwing an error if the promise is realised more than once.

  :param prom
    The promise to realise.
  :param v
    The value to send to the promise.
  "
  [prom v]
  (when-not (p/realised? prom)
    (p/realise-error prom v)))

(defprotocol IChan
  (-put!
    [this value]
    [this value tag prom])
  (-take!
    [this]
    [this tag prom])
  (-alts!
    [this cb-prom]
    [this cb-prom tag prom])
  (-close!
    [this]
    [this tag prom])
  (-closed? [this])
  (-dead? [this])
  (-set-buffer-size! [this buff]))

(let [atom? #(instance? Atom %)
      prom-inside? #(p/promise? (deref %))
      nil-inside? #(nil? (deref %))
      symbol-inside? #(symbol? (deref %))
      queue-inside? #(instance? cljs.core/PersistentQueue (deref %))
      bool-inside? #(boolean? (deref %))
      pos-int-inside? #(and (int? (deref %))
                            (pos? (deref %)))]

  (s/def ::chan-id symbol?)
  (s/def ::chan-transformer fn?)
  (s/def ::poll-interval int?)
  (s/def ::take-ready (s/and atom? prom-inside?))
  (s/def ::put-ready (s/and atom? prom-inside?))
  (s/def ::buffer-size (s/and atom? pos-int-inside?))
  (s/def ::chan-lock (s/and atom? (s/or :nil-in nil-inside?
                                        :sym-in symbol-inside?)))
  (s/def ::put-promises (s/and atom? queue-inside?))
  (s/def ::take-promises (s/and atom? queue-inside?))
  (s/def ::values-queue (s/and atom? queue-inside?))
  (s/def ::next-take-promise (s/and atom? (s/or :nil-in nil-inside?
                                                :prom-in prom-inside?)))
  (s/def ::alts-cb-promise (s/and atom? (s/or :nil-in nil-inside?
                                              :prom-in prom-inside?)))
  (s/def ::is-closed? (s/and atom? bool-inside?))
  (s/def ::is-dead? (s/and atom? bool-inside?)))

(s/def ::chan (s/keys :req-un [::chan-id
                               ::chan-transformer
                               ::poll-interval
                               ::take-ready
                               ::put-ready
                               ::buffer-size
                               ::chan-lock
                               ::put-promises
                               ::take-promises
                               ::values-queue
                               ::next-take-promise
                               ::alts-cb-promise
                               ::is-closed?
                               ::is-dead?]))

(defrecord
    ^{:doc "Channel implementation as a record of atoms.

The channel is mostly a mutable object, all mutable fields are atoms.
The fields 'take-ready' and 'put-ready' are used by the main loop to sleep
until these promises gets realised by puts and takes.
"}
    Chan
    [chan-id
     chan-transformer
     poll-interval
     take-ready
     put-ready
     buffer-size
     chan-lock
     put-promises
     take-promises
     values-queue
     next-take-promise
     alts-cb-promise
     is-closed?
     is-dead?]
  cljs.core/IEquiv
  (-equiv [this o]
    (= chan-id (.chan-id o))))

(extend-type Chan
  IChan
  (-closed? [this]
    (deref (:is-closed? this)))
  (-dead? [this]
    (deref (:is-dead? this)))
  (-set-buffer-size! [this buff]
    (when (and (number? buff)
               (pos? buff))
      (reset! (:buffer-size this) buff))))

(def ^:private empty-queue (.-EMPTY cljs.core/PersistentQueue))

(defn chan
  "Create a new channel and start the channel loop to manage it.

  Create the channel with default options or if given any of the optional
  arguments try to validate and use those.

  :param buffer-size
    If the number of buffered puts exceeds 'buffer-size' realise the put promise
    to false and drop it and the value.
    If the number of buffered takes exceeds 'buffer-size' realise the take
    promise to 'nil' and drop it.
    Default: 1024.

  :param transformer
    When a value gets taken from the channel apply this function to it and return
    the result instead.

  :param delay
    To avoid consuming stack space recur on the loop with defer and this delay.
    Could be negative in which case the macro defer will use setImmediate instead
    of setTimeout.
    Default: -1.

  :return
    A channel object to use with put!, take!, etc.
  "
  ([] (chan nil nil nil))
  ([buffer-size] (chan buffer-size nil nil))
  ([buffer-size transformer] (chan buffer-size transformer nil))
  ([buffer-size transformer delay]
   (let [c (map->Chan {:chan-id (gensym "chan-")
                       :poll-interval (or (and (number? delay)
                                               (>= delay 0)
                                               delay)
                                          -1)
                       :chan-transformer (or (and (fn? transformer)
                                                  transformer)
                                             identity)
                       :take-ready (atom (p/promise))
                       :put-ready (atom (p/promise))
                       :buffer-size (or (and (number? buffer-size)
                                             (pos? buffer-size)
                                             (atom buffer-size))
                                        (atom 1024))
                       :chan-lock (atom nil)
                       :put-promises (atom empty-queue)
                       :take-promises (atom empty-queue)
                       :values-queue (atom empty-queue)
                       :next-take-promise (atom nil)
                       :alts-cb-promise (atom nil)
                       :is-closed? (atom false)
                       :is-dead? (atom false)})]
     (chan-loop c)
     c)))

(defn chan?
  "Test if ch is a valid shrimp channel."
  [ch]
  (instance? Chan ch))

(defn set-buffer-size!
  "Modify the buffer-size of a channel after creation.

  It does not affect what is already queued on the channel.
  Next put! or take! operations will have to respect the new buffer limit.

  :param ch
    The shrimp channel.

  :param buffer-size
    The new buffer-size, must be a positive integer.
  "
  [ch buffer-size]
  (-set-buffer-size! ch buffer-size))

(defn- chan-or-throw
  "Throw an exception if the argument isn't a valid shrimp channel.

  :param ch
    The shrimp channel.
  "
  [ch]
  (when-not (instance? Chan ch)
    (throw (js/Error. "Error: invalid shrimp channel!"))))

(defn closed?
  "Return true if the channel is closed, false otherwise.

  Closed channels accept take operations as long as there are values to take,
  after the last value is taken it switches to dead.
  Put operations are refused on a closed channel and realise immediately to false.

  :param ch
    The shrimp channel.
  "
  [ch]
  (-closed? ch))

(defn dead?
  "Return true if the channel is dead, false otherwise.

  A channel is dead when after being closed all values are taken, a dead channel
  is useless, the loop is stopped, put operations immediately realise to false,
  take operations immediately realise to nil.

  :param ch
    The shrimp channel.
  "
  [ch]
  (-dead? ch))

(defn- close-takes!
  "Switch a channel to dead after the values queue has been emptied.

  All remaining take or alts promises are realised to nil.
  "
  [{:keys [take-ready
           take-promises
           next-take-promise
           alts-cb-promise
           is-dead?]}]
  (try-realise @take-ready nil)
  (if (p/promise? @next-take-promise)
    (try-realise @next-take-promise nil))
  (reset! next-take-promise nil)
  (doseq [prom @take-promises]
    (if (p/promise? prom)
      (try-realise prom nil)
      (do (try-realise (first prom) nil)
          (try-realise (second prom) nil))))
  (reset! take-promises empty-queue)
  (when (p/promise? @alts-cb-promise)
    (try-realise @alts-cb-promise nil))
  (reset! alts-cb-promise nil)
  (reset! is-dead? true))

(defn- close-do!
  "Close the channel and realise all the put promises to nil.

  Helper function called by close!.
  "
  [{:keys [chan-lock
           put-promises
           put-ready
           take-promises
           alts-cb-promise
           values-queue
           is-closed?] :as chan} tag prom]
  (reset! is-closed? true)
  (doseq [prom @put-promises]
    (try-realise prom nil))
  (reset! put-promises empty-queue)
  (try-realise @put-ready true)
  (p/realise prom true)
  (reset! chan-lock nil))

(extend-type Chan
  IChan
  (-close!
    ([this] (-close! this (gensym "close!") (p/promise)))
    ([{:keys [is-closed? chan-lock poll-interval] :as this} tag prom]
     (if @is-closed?
       (p/realise prom false)
       (if (= tag (swap! chan-lock #(or %1 %2) tag))
         (close-do! this tag prom)
         (defer poll-interval (-close! this tag prom))))
     prom)))

(defn close!
  "Close a channel if not already closed.

  Retry if the channel is busy.
  "
  [ch]
  (-close! ch))

(defn- swap-take-promises!
  "Advance the take promises queue, handle the case of an alts! promise.
  "
  [{:keys [take-promises
           next-take-promise
           alts-cb-promise
           take-ready]}]
  (let [next-take-prom (peek @take-promises)]
    (swap! take-promises pop)
    (cond
      (vector? next-take-prom)
      (let [[cb-prom res-prom] next-take-prom]
        (reset! next-take-promise res-prom)
        (reset! alts-cb-promise cb-prom))

      (nil? next-take-prom)
      (do
        (reset! next-take-promise nil)
        (reset! take-ready (p/promise)))

      :else
      (reset! next-take-promise next-take-prom))))

(defn- chan-loop-do!
  "Perform a delivery and handle the case of an alts! promise.

  Realise the next take promise with the next value, realise the corresponding
  put promise to true, then advance all queues.
  For alts! that already received a value drop the promise and deliver to the next
  take.
  This is an helper function invoked by chan-loop, it could recur to itself once.

  :param ch
    The shrimp channel.
  :param tag
    A symbol that has been used to acquire the lock.
  "
  [{:keys [chan-lock
           is-closed?
           poll-interval
           take-promises
           take-ready
           put-promises
           put-ready
           values-queue
           next-take-promise
           alts-cb-promise
           chan-transformer] :as chan} tag]

  (let [value (first @values-queue)
        take-prom @next-take-promise
        put-prom (first @put-promises)
        alts-prom @alts-cb-promise]

    (if (p/promise? alts-prom)
      (if (p/realised? alts-prom)
        (do
          (reset! alts-cb-promise nil)
          (swap-take-promises! chan)
          (reset! chan-lock nil)
          (defer poll-interval (chan-loop chan tag)))
        (do
          (reset! alts-cb-promise nil)
          (p/realise take-prom [(chan-transformer value) chan])
          (chan-loop-do! chan tag)))

      (do
        (swap-take-promises! chan)
        (swap! values-queue pop)
        (swap! put-promises pop)

        (when (and (empty? @put-promises)
                   (not @is-closed?))
          (reset! put-ready (p/promise)))

        (reset! chan-lock nil)
        (defer poll-interval (chan-loop chan tag))

        (and put-prom (p/realise put-prom true))
        (when-not (p/realised? take-prom)
          (p/realise take-prom (chan-transformer value)))))))

(defn- chan-loop
  "Call chan-loop-do! to realise the next promise if put and a take are received.

  If the channel is closed and the values queue is empty call close-takes! and
  terminate the loop.

  :param ch
    The shrimp channel.

  :param tag
    A symbol used to acquire the lock, if absent is generated randomly.
  "
  ([ch] (chan-loop ch (gensym "chan-loop")))
  ([{:keys [is-closed?
            take-ready
            put-ready
            values-queue
            poll-interval] :as ch} tag]
   (let [empty-values? (< (count @values-queue) 1)]
     (if (and @is-closed? empty-values?)
       (close-takes! ch)
       (when-realised [@put-ready]
         (if (and @is-closed? empty-values?)
           (close-takes! ch)
           (when-realised [@take-ready]
             (let [lock (:chan-lock ch)]
               (if (= tag (swap! lock #(or %1 %2) tag))
                 (chan-loop-do! ch tag)
                 (defer poll-interval (chan-loop ch tag)))))))))))

(defn- put-do!
  "Perform the put! operation on the channel.

  Helper function called by put!.
  "
  [{:keys [chan-lock
           is-closed?
           buffer-size
           put-ready
           put-promises
           values-queue]} value tag prom]
  (if (>= (count @values-queue) @buffer-size)
    (p/realise prom false)
    (do
      (try-realise @put-ready true)
      (swap! put-promises conj prom)
      (swap! values-queue conj value)))
  (reset! chan-lock nil))

(extend-type Chan
  IChan
  (-put!
    ([this value] (-put! this value (gensym "put!") (p/promise)))
    ([{:keys [chan-lock poll-interval is-closed?] :as this}
      value tag prom]
     (if @is-closed?
       (p/realise prom false)
       (if (= tag (swap! chan-lock #(or %1 %2) tag))
         (put-do! this value tag prom)
         (defer poll-interval (-put! this value tag prom))))
     prom)))

(defn put!
  "Put a value in the channel, return a promise realised when the value is taken.

  Refuse the put! and immediately realise the return promise to 'false' if the
  channel is closed or the buffer is full.

  The return promise is realised to 'true' if the value is taken.
  If the channel is closed after the put! but before a take!, the return promise
  will be realised to 'nil'.

  :param ch
    The shrimp channel.

  :param value
    The value to put in the channel.

  :return
    A callback promise realised after a take or on failure.
  "
  ([ch value] (-put! ch value)))

(defn- take-do!
  "Perform the take! operation on the channel.

  Helper function called by take!.
  "
  [{:keys [chan-lock
           is-closed?
           take-promises
           next-take-promise
           take-ready
           values-queue
           buffer-size]} tag prom]

  (if (>= (count @take-promises) @buffer-size)
    (p/realise prom nil)
    (if @next-take-promise
      (swap! take-promises conj prom)
      (do
        (try-realise @take-ready true)
        (reset! next-take-promise prom))))
  (reset! chan-lock nil))

(extend-type Chan
  IChan
  (-take!
    ([this] (-take! this (gensym "take!") (p/promise)))
    ([{:keys [chan-lock poll-interval is-dead?] :as this} tag prom]
     (if @is-dead?
       (p/realise prom nil)
       (if (= tag (swap! chan-lock #(or %1 %2) tag))
         (take-do! this tag prom)
         (defer poll-interval (-take! this tag prom))))
     prom)))

(defn take!
  "Perform a take on the channel, return a promise realised with the value.

  Refuse the take and immediately realise the return promise to 'nil' if the
  channel is dead, or the buffer is full.
  The return promise is placed on the channel and realised as soon as a value is
  available and this promise is the first in the queue.

  :param ch
    The shrimp channel.

  :param tag
    A symbol used to acquire the lock, randomly generated if absent.

  :return
    A promise realised to a value from the channel or 'nil' on failure.
  "
  ([ch] (-take! ch)))

(defn timeout
  "Create a timeout and return a promise that is realised on expiration.

  This method is used by alts! to provide a timeout with an optional default
  value.

  :param ms
    Delay in milliseconds before the expiration.

  :param value
    Realise the returned promise to this value on expiration.

  :param success-fn
    Optional callback attached to the return promise and called on realisation.

  :param failure-fn
    Optional callback called when the return promise is realised with
    'realise-error'.

  :return
    A promise realised on expiration.
  "
  ([ms] (timeout ms nil nil nil))
  ([ms value success-fn error-fn]
   (let [prom (p/promise)]
     (when (and success-fn error-fn)
       (p/on-realised prom
                      success-fn
                      error-fn))
     (js/setTimeout #(p/realise prom value) ms)
     prom)))

(defn- alts-do!
  "Perform an alts! on the channel.

  helper function invoked by alts!.
  "
  [{:keys [chan-lock
           is-closed?
           take-promises
           take-ready
           values-queue
           next-take-promise
           alts-cb-promise
           buffer-size]} tag cb-prom res-prom]

  (if (>= (count @take-promises) @buffer-size)
    (do
      (p/realise cb-prom nil)
      (p/realise res-prom nil))
    (if @next-take-promise
      (swap! take-promises conj [cb-prom res-prom])
      (do
        (try-realise @take-ready true)
        (reset! next-take-promise res-prom)
        (reset! alts-cb-promise cb-prom))))
  (reset! chan-lock nil))

(extend-type Chan
  IChan
  (-alts!
    ([this cb-prom] (-alts! this (gensym "alts!") cb-prom (p/promise)))
    ([{:keys [chan-lock poll-interval] :as this}
      tag cb-prom res-prom]
     (if (= tag (swap! chan-lock #(or %1 %2) tag))
       (alts-do! this tag cb-prom res-prom)
       (defer poll-interval (-alts! this tag cb-prom res-prom)))
     res-prom)))

(defn alts!
  "Try to take from one or more channels, return a promise realised to the first
  available value.

  Realise the promise to a vector [value channel].
  Immediately realise the promise to '[nil :dead]' if one or more channels are
  dead.
  If 'ttime' is provided start a timer with delay 'ttime' that will realise the
  alts! promise to '[tvalue :expired]', 'tvalue' defaults to 'nil' if absent.

  :param channels
    A seq of shrimp channels.

  :param ttime
    Timeout delay in milliseconds.

  :param tvalue
    Timeout value to use to realise the alts! promise with.

  :return
    A promise realised to a value from a channel, or '[nil :dead]' on failure,
    or '[tvalue :expired]' if ttime was defined and no channel had a value to
    take before the expiration.
  "
  ([channels] (alts! channels nil nil))
  ([channels ttime] (alts! channels ttime nil))
  ([channels ttime tvalue]
   (let [dtoll (for [ch channels]
                 (do (chan-or-throw ch)
                     (deref (:is-dead? ch))))]

     (if (some true? dtoll)
       (p/promise [nil :dead])
       (let [res-prom (p/promise)
             realise-cb (fn [cb]
                          (fn [value]
                            (try-realise cb true)
                            (try-realise res-prom value)))

             realise-err-cb (fn [cb]
                              (fn [value]
                                (try-realise cb true)
                                (try-realise-error res-prom value)))
             cb-prom (p/promise)
             realised (fn [value]
                        (try-realise cb-prom true)
                        (try-realise res-prom value))
             realised-err (fn [value]
                            (try-realise cb-prom true)
                            (try-realise-error res-prom value))
             proms (doall (map #(-alts! % cb-prom) channels))]

         (doseq [alts-prom proms]
           (p/on-realised alts-prom
                          (realise-cb cb-prom)
                          (realise-err-cb cb-prom)))

         (when ttime
           (timeout ttime
                    [tvalue :expired]
                    realised
                    realised-err))

         res-prom)))))
