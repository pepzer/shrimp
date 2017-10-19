## What is Shrimp?

Shrimp is a [ClojureScript](https://clojurescript.org/) library that implements asynchronous communication channels, built on top of [Red Lobster](https://github.com/whamtet/redlobster) promise library.  
It targets [Node.js](https://nodejs.org/en/) and [Lumo](https://github.com/anmonteiro/lumo) with the aim to be lightweight and to offer in addition useful functionalities for testing async functions.  
Shrimp is *not* meant to replace [core.async](https://github.com/clojure/core.async) on these platforms, there is no plan to offer the same functionalities and no attempt to hide the underlying promise API.  

Almost all operations on channels in Shrimp return a Red Lobster promise that could be managed with functions and macros from the same library.  
In addition Shrimp offers an async supporting loop macro, and there is also [Shrimp-Chain](https://github.com/pepzer/shrimp-chain), a collection of macros built on top of Shrimp providing a unified way to manage multiple async operations returning promises intertwined with synchronous ones.

## Rationale

While there is evidence to support the diagnosis of NIH syndrome, this project is motivated by the relative complexity/overhead of using core.async with Lumo.  
Shrimp should be fast at compile and run-time and it only requires one additional dependency.  
Also it's fun to experiment!

## Leiningen/Clojars/Lumo

[![Clojars Project](https://img.shields.io/clojars/v/shrimp.svg)](https://clojars.org/shrimp)

If you use [Leiningen](https://github.com/technomancy/leiningen) add redlobster and shrimp to the dependencies in your project.clj file.
  
    :dependencies [... 
                   [org.clojars.pepzer/redlobster "0.2.2"]
                   [shrimp "0.1.0"]]
    
For Lumo you could either download the dependencies with Leiningen/Maven and specify the libraries on the CLI this way:

    $ lumo -D org.clojars.pepzer/redlobster:0.2.2,shrimp:0.1.0
    
Or you could download the jar files and add them to Lumo classpath:

    $ lumo -c redlobster-0.2.2.jar:shrimp-0.1.0.jar 
    
## Note on Red Lobster

The release of Red Lobster listed above is my version, the reason is a small fix that avoids annoying (especially with Lumo) warnings on compilation.  
I will send a pull request with this fix and if the official release gets updated i will switch to that as a dependency.

## REPL

To run a REPL in the project directory you could either use lein figwheel (optionally with rlwrap):
   
    $ rlwrap lein figwheel dev

With Node.js and npm installed open a shell, navigate to the root of the project and run:

    $ npm install ws
    $ node target/out/shrimp.js

Then the REPL should connect in the lein figwheel window.

With Lumo installed just run the lumo-repl.cljsh script:
   
    $ bash lumo-repl.cljsh
    
This will run the REPL and will also listen on the port 12345 of the localhost for connections.  
You could connect with Emacs and inf-clojure-connect.

## Usage

To use shrimp, require the shrimp.core namespace, and create a channnel:

    (require '[shrimp.core :as sc])
   
    (def chan1 (sc/chan))
    
To close a channel:

    (sc/close! chan1)

A closed channel allows to take! until the values-queue is empty, then it switches to dead, to test the channel state:
    
    (sc/closed? chan1)
    
    (sc/dead? chan1)

A channel has a buffer-size that defines the maximum dimension of the queue for both put! and take! operations.  
After the number of puts or takes reaches the buffer-size, a new call will fail (i.e. return a promise already realised to respectively false and nil for put! and take!/alts!).  
The default limit for the buffer is 1024, a different value could be specified on creation:

    (def chan2 (sc/chan 20))

### put! and take!

Require Red Lobster macros with use-macros to manage the channel promises:

    (require '[shrimp.core :as sc])
    (use-macros '[redlobster.macros :only [let-realised]])
    
    ; Define the channel
    (def chan1 (sc/chan))
    
    ; Try to take from the channel
    ; Print the value when the promise is realised
    (let-realised [prom (sc/take! chan1)]
     (do (println "Val: " @prom)
       (sc/close! chan1)))
       
    ; Put a value inside the channnel
    (sc/put! chan1 "foo")

    => Val: foo
    
### alts!

There is an alts! function to take from the first available channel with an optional timeout and corresponding default value:

    (require '[shrimp.core :as sc])
    (use-macros '[redlobster.macros :only [let-realised]])
    
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
       (sc/close! chan1)))
       
    ; Put a value inside the channnel
    (sc/put! chan1 "foo")

    => Val: foo , from chan1

To define a timeout of 1 second and a default value on expiration:

    (ps/alts! [chan1 chan2] 1000 "default value")

### defer-loop

This macro mimics Clojure's loop, but it allows to use asynchronous functions inside the loop:

    (require '[shrimp.core :as sc])
    (use-macros '[redlobster.macros :only [when-realised]])
    (use-macros '[shrimp.macros :only [defer-loop]])
    
    ; Define the channel
    (def chan1 (sc/chan))
    
    ; The loop stops when the take! promise realises to nil
    (defer-loop [prom (sc/take! chan1)]
      (when-realised [prom]
        (if @prom
          (do
            (println "Val: " @prom ", from defer-loop")

            ; defer-recur works like recur
            ; It is only defined under the scope of the defer-loop macro
            (defer-recur (sc/take! chan1)))

          (println "Exit from the loop"))))
    
    ; Put a value inside the channnel
    (sc/put! chan1 "foo")
    
    (sc/close! chan1)

    => Val: foo , from defer-loop
    Exit from the loop

## Extras and Testing

There are other macros in shrimp that might be useful in particular for testing.

### defer

This is a slight variation on the Red Lobster's defer macro, it allows to defer the execution of an expression with a delay.  
Compared to Red Lobster, this version always requires the integer value for the delay, but now it could be a var in addition to a literal number, also if the delay is any negative value defer will use js/setImmediate.

    (use-macros '[shrimp.macros :only [defer]])
    
    (defer 2000 (println "foo"))

    => foo

### defer-time

This small macro allows to easily print the elapsed time for an asynchronous function. The function do-time is defined inside the scope of the macro, it should be called as the last expression in the asynchronous block (or as a wrapper for it):

    (use-macros '[shrimp.macros :only [defer-time defer]])
    
    (defer-time 
      (defer 2000 (do (println "foo")
                    (do-time (println "bar")))))
                    
    => foo
    bar
    "Elapsed time: 2003.601113 msecs"
    
### Testing asynchronous functions

Shrimp provides a macro and the necessary helper functions to run asynchronous tests and receive correct error reports. The differences compared to standard tests are minimal.  
First create a test namespace to run all the other tests with the run-async-tests macro:
    
    (ns foo.all-tests
      (:require [foo.core-test]
                [foo.bar-test])
      (:use-macros [shrimp.test.macros :only [run-async-tests]]))
      
    (defn -main []
      (run-async-tests
        foo.core-test
        foo.bar-test))

The only addition to the tests is a call to the done! function at the end of each deftest, a call to done! is required for *ALL* tests even for synchronous ones:

    (ns foo.core-test
      (:require [cljs.test :refer [deftest is]]
                [shrimp.test :as st])
      (:use-macros [shrimp.macros :only [defer]]))
      
    (deftest sync-test
      (is (= 3 (+ 1 2)))
      (st/done!))
      
    ; Call st/done! as the last expression in the async block
    (deftest async-test
      (defer 2000 (do
                    (is (= 1 1))
                    (st/done!))))

To run the tests invoke the main of foo.all-tests, for example with Lumo:
 
    $ lumo -c ... -m foo.all-tests
    
The output should be similar to this:
     
    Testing foo.core-test
    
    Ran 2 tests containing 2 assertions.
    0 failures, 0 errors.
    
    Testing foo.bar-test

    Ran 2 tests containing 2 assertions.
    0 failures, 0 errors.

    All namespaces:

    Ran 4 tests containing 4 assertions.
    0 failures, 0 errors.
    
## Tests

To run the tests with Leiningen use:

    $ lein cljsbuild once
    $ node target/out-test/shrimp.js
    
With Lumo:

    $ bash lumo-test.sh
    
## Code Maturity

This is an early release, hence bugs should be expected and future releases could break the current API.
    
## Contacts

[Giuseppe Zerbo](https://github.com/pepzer), [giuseppe (dot) zerbo (at) gmail (dot) com](mailto:giuseppe.zerbo@gmail.com).

## License

Copyright © 2017 Giuseppe Zerbo.  
Distributed under the [Mozilla Public License, v. 2.0](http://mozilla.org/MPL/2.0/).
