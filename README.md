scope [![Build Status](https://travis-ci.org/PhantomThief/scope.svg)](https://travis-ci.org/PhantomThief/scope) [![Coverage Status](https://coveralls.io/repos/PhantomThief/scope/badge.svg?branch=master)](https://coveralls.io/r/PhantomThief/scope?branch=master)
=======================

对ThreadLocal的高级封装

* 显示的声明Scope的范围
* 强类型
* 可以在线程池中安全的使用，并防止泄露
* 只支持jdk1.8

## Get Started

```xml
<dependency>
    <groupId>com.github.phantomthief</groupId>
    <artifactId>scope</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Usage

```Java

private static final ScopeKey<String> TEST_KEY = allocate();

public void basicUse() {
    runWithNewScope(() -> {
         TEST_KEY.set("abc");
         String result = TEST_KEY.get(); // get "abc"
            
         runAsyncWithCurrentScope(()-> {
             String resultInScope = TEST_KEY.get(); // get "abc"
         }, executor);
    });
}
```