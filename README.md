scope [![Build Status](https://travis-ci.org/PhantomThief/scope.svg)](https://travis-ci.org/PhantomThief/scope) [![Coverage Status](https://coveralls.io/repos/PhantomThief/scope/badge.svg?branch=master)](https://coveralls.io/r/PhantomThief/scope?branch=master) [![Total alerts](https://img.shields.io/lgtm/alerts/g/PhantomThief/scope.svg?logo=lgtm&logoWidth=18)](https://lgtm.com/projects/g/PhantomThief/scope/alerts/) [![Language grade: Java](https://img.shields.io/lgtm/grade/java/g/PhantomThief/scope.svg?logo=lgtm&logoWidth=18)](https://lgtm.com/projects/g/PhantomThief/scope/context:java) 
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
    <version>1.0.15</version>
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

// 或者声明一个Scope友好的ExecutorService，方法如下:
private static class ScopeThreadPoolExecutor extends ThreadPoolExecutor {

    ScopeThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime,
            TimeUnit unit, BlockingQueue<Runnable> workQueue) {
        super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue);
    }

    /**
     * same as {@link java.util.concurrent.Executors#newFixedThreadPool(int)}
     */ 
    static ScopeThreadPoolExecutor newFixedThreadPool(int nThreads) {
        return new ScopeThreadPoolExecutor(nThreads, nThreads, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>());
    }

    /**
     * 只要override这一个方法就可以
     * 所有submit, invokeAll等方法都会代理到这里来
     */
    @Override
    public void execute(Runnable command) {
        Scope scope = getCurrentScope();
        super.execute(() -> runWithExistScope(scope, command::run));
    }
}

private ExecutorService executor = ScopeThreadPoolExecutor.newFixedThreadPool(10);

public void executeTest() {
    runWithNewScope(() -> {
       TEST_KEY.set("abc");
       executor.submit(() -> {
           TEST_KEY.get(); // get abc
       });
    });
}
```