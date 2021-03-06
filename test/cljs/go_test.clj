(ns cljs.go-test
  (:refer-clojure :exclude [test])
  (:require [clojure.test :refer :all]
            [cljs.go :refer :all]
            [cljs.go.compiler :as cljs.compiler]
            [cljs.analyzer :as ana]
            [cljs.env :as env]
            [cljs.tagged-literals]
            [clojure.pprint :as pp]
            [clojure.string :as s]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh])
  (:import [java.io Writer]
           [cljs.tagged_literals JSValue]))

(defn pp [x]
  (binding [pp/*print-right-margin* 80]
    (pp/pprint x)))

(def core-env nil)

(defn cache-core! []
  (def core-env (env/ensure
                 (cljs.compiler/with-core-cljs {}
                   (fn [] env/*compiler*)))))

(defmacro tdd [& body]
  `(binding [env/*compiler* core-env]
     (env/ensure
      (cljs.compiler/with-core-cljs {}
        (fn []
          (with-fresh-ids
            (def *ast (cljs->ast '[~@body]))
            (def *go (s/trim (goimports (ast->go *ast))))
            (def *ns (ana/get-namespace ana/*cljs-ns*))
            (pp *ast)
            (println)
            (println *go)))))))

(defn combined-output [out err]
  (s/replace (s/replace (str err out) "\r" "\n") "\n\t\t" ""))

(defn go-test [package]
  (let [{:keys [out err exit]} (sh/sh "go" "test" (str package))]
    (is (zero? exit) (combined-output out err))))

(defn test-header [package & imports]
  (with-out-str
    (println "package" package)
    (println "import" "cljs_core" (pr-str "github.com/hraberg/cljs2go/cljs/core"))))

(def ^:dynamic *ast-debug* false)

(defn test-comment [code ast]
  (println "/*")
  (pp code)
  (when *ast-debug*
    (println)
    (pp ast))
  (println"*/"))

(defn test-setup [setup]
  (with-out-str
    (let [ast (cljs->ast setup)]
      (test-comment setup ast)
      (printf "\t%s\n" (ast->go ast)))))

(defn test-assertions [assertions]
  (doseq [[expected actual] (partition 2 assertions)
          :let [ast (-> [actual] (cljs->ast (assoc (cljs.analyzer/empty-env) :context :expr)))]]
    (test-comment actual (first ast))
    (printf "\tassert.Equal(t,\n %s,\n %s)\n" expected (ast->go ast))))

(defn test [test & assertions]
  (with-out-str
    (printf "func Test_%s(t *testing.T) {\n" (name test))
    (test-assertions assertions)
    (printf "}\n\n")))

(defn bench [benchmark & assertions]
  (with-out-str
    (printf "func Benchmark_%s(t *testing.B) {\n" (name benchmark))
    (test-assertions assertions)
    (printf "}\n\n")))

(defn emit-test [package file tests]
  (go-get "github.com/stretchr/testify/assert")
  (doto (io/file (str "target/generated/" package) (str file ".go"))
    io/make-parents
    (spit (goimports (apply str (test-header package) tests)))))

(defn constants []
  (->>
   [(test "Constants"
          "nil" nil
          true true
          false false
          1 1
          3.14 3.14
          2 '(inc 1)
          10 '(* 4 2.5)
          "`foo`" "foo"
          "`x`" \x
          "map[string]interface{}{`foo`: `bar`}" (read-string "#js {:foo \"bar\"}")
          "[]interface{}{\"foo\", \"bar\"}" (read-string "#js [\"foo\", \"bar\"])")
          "&js.Date{Millis: 1408642409602}" #inst "2014-08-21T17:33:29.602-00:00"
          "&cljs_core.CljsCoreUUID{Uuid: `15c52219-a8fd-4771-87e2-42ee33b79bca`}" #uuid "15c52219-a8fd-4771-87e2-42ee33b79bca"
          "&js.RegExp{Pattern: `x`, Flags: ``}" #"x"
          "&js.RegExp{Pattern: ``, Flags: ``}" #""
          "&cljs_core.CljsCoreSymbol{Ns: nil, Name: `x`, Str: `x`, X_hash: float64(-555367584), X_meta: nil}" ''x
          "&cljs_core.CljsCoreSymbol{Ns: `user`, Name: `x`, Str: `user/x`, X_hash: float64(-568535109), X_meta: nil}" ''user/x
          "&cljs_core.CljsCoreKeyword{Ns: nil, Name: `x`, Fqn: `x`, X_hash: float64(2099068185)}" :x
          "&cljs_core.CljsCoreKeyword{Ns: `user`, Name: `x`, Fqn: `user/x`, X_hash: float64(2085900660)}" :user/x)]
   (emit-test "go_test" "constants_test")))

(defn special-forms []
  (->>
   [(test "Let"
          1 '(let [y 1]
               y)
          "nil" '(let [x nil]
                   x))
    (test "Letfn"
          "`bar`" '(letfn [(foo [] "bar")]
                     (foo))
          true '(letfn [(even? [x]
                          (or (zero? x)
                              (odd? (dec x))))
                        (odd? [x]
                          (and (not (zero? x))
                               (even? (dec x))))]
                  (odd? 5)))
    (test "If"
          true '(let [y :foo]
                  (if y true false))
          1 '(let [y true
                   z (if y 1 0)]
               z)
          1 '(letfn [(y [] true)]
               (if (y) 1 2))
          1 '(letfn [(y [] "")]
               (if (y) 1 2))
          2 (letfn [(^seq y [])]
              (if (y) 1 2))
          2 (letfn [(y [])]
              (if (y) 1 2)))
    (test "Loop"
          5 '(loop [y 0]
               (if (== y 5)
                 y
                 (recur (inc y)))))
    (test "Do"
          3 '(do 1 2 3))
    (test-setup '[(def x 2)])
    (test "Def"
          2 'x)
    (test-setup '[(defn foo [] "bar")])
    (test "Defn"
          "`bar`" '(foo))
    (test-setup '[(defn bar
                    ([x] x)
                    ([x y] y))
                  (defn baz
                    ([] (foo))
                    ([x] x)
                    ([x & ys] (into-array ys)))])
    (test "Invoke"
          "`bar`" '(letfn [(bar [f] (f))]
                     (bar foo))
          1 '(bar 1)
          2 '(bar 1 2)
          "`bar`" '(baz)
          1 '(baz 1)
          "[]interface{}{2, 3}" '(baz 1 2 3)
          ;; last arg here to apply should be a seq
          "[]interface{}{2, 3, 4}" '(apply baz 1 2 [3 4])
          "`bar`" '((fn [x] x) "bar")
          3.14 '(js/ParseFloat "3.14")
          3 '(Math/floor 3.14)
          832040 '((fn fib [n]
                     (cond (zero? n) 0
                           (== 1 n) 1
                           :else (+ (fib (dec n)) (fib (- n 2))))) 30))
    (test "New"
          "&js.Date{Millis: 0}" '(js/Date. 0)
          "&js.Date{Millis: 0}" '(new js/Date 0))
    (test "Dot"
          1970 '(.getUTCFullYear (js/Date. 0))
          "`f`" '(.charAt "foo" 0)
          3 '(.-length "foo")
          "`o`" '(let [x "foo"]
                   (.charAt x 1))
          "`15c52219-a8fd-4771-87e2-42ee33b79bca`" '(.-uuid (UUID. "15c52219-a8fd-4771-87e2-42ee33b79bca")))
    (test-setup '[(deftype MyPoint [x y])

                  (defprotocol IFoo
                    (-bar [foo x]))

                  (deftype MyFooWithArg []
                    IFoo
                    (-bar [_ x] x))

                  (deftype MyFooWithThis []
                    IFoo
                    (-bar [this _] this))

                  (def foo-with-this (MyFooWithThis.))

                  (deftype MyFooWithField [field]
                    IFoo
                    (-bar [_ _] field))

                  (deftype AnObject []
                    Object
                    (toString [_] "baz")
                    (equiv [this other] false)

                    IFoo
                    (-bar [this x] (str this x)))

                  (defprotocol MyIEquiv
                    (^boolean -my-equiv [o other]))

                  (defprotocol IMarker)

                  (deftype MyFooEquiv [str]
                    IMarker
                    Object
                    (toString [_] str)
                    (equiv [this other]
                      (-my-equiv this other))

                    MyIEquiv
                    (-my-equiv [_ _]
                      false))

                  (defn foo-str [x]
                    (str "foo" x))

                  (deftype MyInferenceType []
                    Object
                    (toString [this] (foo-str "bar")))])
    (test "Deftype"
          "&CljsUserMyPoint{X: 1, Y: 2}" '(MyPoint. 1 2)
          "&CljsUserMyPoint{X: 1, Y: 2}" '(->MyPoint 1 2)
          3 '(.-x (MyPoint. 3 4))
          "`CljsUserIFoo`" '(js* "reflect.TypeOf((*CljsUserIFoo)(nil)).Elem().Name()")
          2 '(js* "reflect.TypeOf((*CljsUserIFoo)(nil)).Elem().NumMethod()")
          "`foo`" '(-bar (MyFooWithArg.) "foo")
          "`foo`" '(apply -bar (MyFooWithArg.) ["foo"])
          "Foo_with_this" '(-bar (MyFooWithThis.) "foo")
          0 '(-bar (MyFooWithField. 0) nil)
          true '(satisfies? cljs.user/IFoo foo-with-this)
          true '(satisfies? IFoo foo-with-this)
          false '(satisfies? cljs.user/IFoo (MyPoint. 0 0))
          "`baz`" '(.toString (AnObject.))
          "`baz`" '(.string (AnObject.))
          false '(.equiv (AnObject.) "foo")
          true '(satisfies? Object (AnObject.))
          "`bazbar`" '(-bar (AnObject.) "bar")
          false '(satisfies? IMarker (AnObject.))

          "`bar`" '(str (MyFooEquiv. "bar"))
          false '(-my-equiv (MyFooEquiv. "bar") nil)
          true '(satisfies? IMarker (MyFooEquiv. "bar"))
          "`foobar`" '(str (MyInferenceType.)))
    (test "Var"
          "math.Inf(1)" 'js/Infinity)
    (test-setup '[(def y 2)
                  (deftype HasFields [x])
                  (deftype HasStaticFields [])
                  (set! (.-ZERO HasStaticFields) 0)
                  (set! (.-newFoo HasStaticFields) (fn [] "foo"))])
    (test "Set_BANG_"
          2 'y
          3 '(do (set! y 3) y)
          4 '(set! y 4)
          "`foo`" '(set! (.-x (HasFields. "bar")) "foo")
          "`foo`" '((fn [o] (set! (.-x o) "foo")) (HasFields. "bar"))
          0 '(.-ZERO HasStaticFields)
          "`foo`" '(.newFoo HasStaticFields)
          -1 '(set! (.-ZERO HasStaticFields) -1))
    (test "Case_STAR_"
          true '(let [x 2]
                  (case x
                    2 true
                    1 false
                    0)))
    (test "Js_STAR_"
          "reflect.Float64" '(let [x 1]
                               (js* "reflect.ValueOf(x).Kind()"))
          "reflect.Int" '(let [v (js* "reflect.ValueOf(1)")]
                           (-> v .Type .Kind)))
    (test "Try"
          "&js.Error{`Foo`}" '(try
                                (throw (js/Error. "Foo"))
                                (catch js/Error e
                                  e))
          "`TypeError`" '(try
                           (throw (js/TypeError. "Foo"))
                           (catch js/Error _
                             "Error")
                           (catch js/TypeError _
                             "TypeError"))
          "`Bar`" '(try
                     "Bar"
                     (catch js/Error e
                       e)
                     (finally
                       "Baz"))
          "map[string]interface{}{`finally`: true}"
          '(let [x (js* "map[string]interface{}{}")]
             (try
               x
               (finally
                 (js* "x[`finally`] = true"))))
          "map[string]interface{}{`catch`: true, `finally`: true, `last`: `finally`}"
          '(let [x (js* "map[string]interface{}{}")]
             (try
               (throw (js/Error. "Foo"))
               (catch js/Error _
                 (js* "x[`catch`] = true")
                 (js* "x[`last`] = `catch`")
                 x)
               (finally
                 (js* "x[`finally`] = true")
                 (js* "x[`last`] = `finally`")))))]
   (emit-test "go_test" "special_forms_test")))

;; these are generated and compiled, but not actually run during the tests
(defn benchmarks []
  (->>
   [(bench "Fibonacci"
           832040 '((fn fib [n]
                      (cond (zero? n) 0
                            (== 1 n) 1
                            :else (+ (fib (dec n)) (fib (- n 2))))) 30))
    (bench "FibonacciPrimtitves"
           832040 '((fn ^number fib [^number n]
                      (cond (zero? n) 0
                            (== 1 n) 1
                            :else (+ (fib (dec n)) (fib (- n 2))))) 30))
    (bench "Factorial"
           2432902008176640000
           '((fn fact
               ([n] (fact n 1))
               ([n f]
                  (if (== n 1)
                    f
                    (recur (dec n) (* f n))))) 20))]
   (emit-test "go_test" "benchmarks_test")))

(defn clojurescript-tests
  ([] (clojurescript-tests
       "."
       '[cljs.core-test
         cljs.reader-test
         cljs.binding-test-other-ns
         cljs.binding-test
         cljs.macro-test
         cljs.letfn-test
         cljs.ns-test.bar
         cljs.ns-test.foo
         cljs.ns-test
         clojure.string-test
         clojure.data-test
         baz
         foo.ns-shadow-test
         cljs.top-level-test
         cljs.keyword-other
         cljs.keyword-test]))
  ([target namespaces]
      (doseq [:let [go-project-path (go-path-prefix target)]
              ns namespaces]
        (binding [cljs.compiler/*go-import-prefix* (merge cljs.compiler/*go-import-prefix*
                                                          (zipmap namespaces (repeat go-project-path)))]
          (compile-file target (io/file (ns-to-resource ns "cljs")))))))

(deftest go-all-tests
  (binding [cljs.analyzer/*cljs-file* (:file (meta #'go-test))
            cljs.compiler/*go-line-numbers* true
            *ast-debug* false
            cljs.compiler/*go-def-vars* true
            cljs.compiler/*go-verbose* false
            *data-readers* cljs.tagged-literals/*cljs-data-readers*]
    (doseq [gen [constants special-forms benchmarks clojurescript-tests]]
      (with-fresh-ids
        (env/ensure
         (cljs.compiler/with-core-cljs {} gen))))
    (go-test "./...")))

(defn run-benchmarks []
  (doseq [dir ["." "target/generated/go_test"]]
    (let [{:keys [out err exit]} (sh/sh "go" "test" "-bench" "." :dir dir)]
      (println (combined-output out err)))))

(defmethod print-method cljs.tagged_literals.JSValue
  [^JSValue d ^Writer w]
  (.write w "#js ")
  (print-method (.val d) w))
