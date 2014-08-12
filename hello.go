// hello
package main

import "fmt"

// import (
// 	. "cljs/core"
// 	"js"
// )

var _STAR_print_fn_STAR_ func(...interface{}) interface{}

func Println(xs ...interface{}) interface{} {
	_STAR_print_fn_STAR_(xs...)
	return nil
}

func Set_print_fn_BANG_(f func(...interface{}) interface{}) {
	_STAR_print_fn_STAR_ = f
}

func init() {
	Set_print_fn_BANG_(func(xs ...interface{}) interface{} {
		fmt.Println(xs...)
		return nil
	})
}

type js_Error struct {
	error string
}

func (e js_Error) Error() string {
	return e.error
}

var Foo_cljs__core__IFn___invoke__arity__0 func() interface{}
var Foo_cljs__core__IFn___invoke__arity__1 func(interface{}) interface{}
var Foo func(...interface{}) interface{}

func init() {
	Foo_cljs__core__IFn___invoke__arity__0 = func() interface{} {
		return Foo("World")
	}
	Foo_cljs__core__IFn___invoke__arity__1 = func(x interface{}) interface{} {
		return Println("Hello ", x)
	}
	Foo = func(arguments ...interface{}) interface{} {
		switch len(arguments) {
		case 0:
			return Foo_cljs__core__IFn___invoke__arity__0()
		case 1:
			return Foo_cljs__core__IFn___invoke__arity__1(arguments[0])
		}
		panic(js_Error{fmt.Sprint("Invalid arity: ", len(arguments))})
	}
}

func main() {
	Foo()
	Foo("Space")
	Foo_cljs__core__IFn___invoke__arity__0()
	Foo_cljs__core__IFn___invoke__arity__1("Space")
	Foo("Space", "Hyper")
}
