### 简介：
通过attach agent来实现对debuggable app(release app需root)的动态代码注入，无需依赖额外工具。

<br/>

### 博客详情： 敬请期待。。。

<br/>

### 姊妹篇：
[jdwp-injector-for-android](https://github.com/wuyr/jdwp-injector-for-android)（借助Android 11以上自带的无线adb，给普通手机(无root)提供一个对debuggable app进行动态代码注入的能力)

<br/>

### 效果预览：

**将要注入的代码：**

```kotlin
fun showDialog() {
    thread {
        Handler(Looper.getMainLooper()).post {
            runningActivities.forEach {
                if (!it.isDestroyed) {
                    AlertDialog.Builder(it).setMessage("Hello Agent from $application")
                        .setPositiveButton("close", null).show()
                }
            }
            Toast.makeText(application, "dialog has been showed", Toast.LENGTH_LONG).show()
        }
    }
}
```

>完整代码请移步: [Drug.kt](https://github.com/wuyr/agent-injector-for-android/blob/master/app/src/main/java/com/wuyr/agent_injector_test/Drug.kt)

<br/>

**运行效果：(需要科学上网)**

![preview](https://github.com/wuyr/agent-injector-for-android/raw/main/previews/1.gif)
![preview](https://github.com/wuyr/agent-injector-for-android/raw/main/previews/2.gif)

>注：如果要注入release版的app，必须开启全局调试(`ro.debuggable=1`)或者当前系统类型是userdebug或eng(`ro.build.type=userdebug|eng`)才可以。
>
> **android 14之后改了判定机制，原来的`ro.debuggable`属性已经没用了，新的全局调试条件改成了`ro.build.type=eng`或者`ro.build.type=userdebug`并且`persist.debug.dalvik.vm.jdwp.enabled=1`**

<br/>

### Demo下载: [app-debug.apk](https://github.com/wuyr/agent-injector-for-android/raw/main/app-debug.apk)

<br/>

### Demo使用方法 (请科学上网以查看图片)：
首次运行app，请先按照指引，进行无线配对:

![preview](https://github.com/wuyr/agent-injector-for-android/raw/main/previews/3.png)

配对完成，加载出app列表之后，可以对列表里的app进行代码注入——你可以尝试在目标app里显示一个dialog，或者显示一个toast。

通常情况下，如果你的手机没有开启全局调试，它只能对debug包进行注入，如果你想要注入release版app，可以点击页面上方的 "设为全局可调试" 按钮开启全局调试(有安装magisk的设备才会显示此按钮)：

![preview](https://github.com/wuyr/agent-injector-for-android/raw/main/previews/4.png)

等待自动重启之后，重新打开app，你会发现，release版app也能够注入了。

**注意：** 一些系统app，可能会通过selinux禁掉data目录的执行权限（如果你注入系统app没有任何反应并且app没有闪退的话，多半是被selinux拦截了），针对这种情况，需要先关闭selinux才能正常注入，可通过页面上方的 "关闭SELINUX" 按钮进行关闭(需要root权限)。

当然，这只是一个功能演示，你完全可以clone之后按照你的想法去改造成你想要的样子。

<br/>

### 诞生背景：
之前在写android端debugger的时候，忽然想到了一个问题：**debugger在设置断点之后，怎样才可以让它主动触发呢？** 因为我要做的是通过debugger来实现代码自动化注入，这个过程中如果需要用户去干预，那就不算自动化了，而且这个触发的时机必须尽量早，不然会影响整体效率。<br/>
第一个想到的方案是，**模拟一个屏幕触摸事件：**<br/>
很多时候确实可以用这个方法，但这个方法有个弊端：因为你无法保证每次的触摸事件都能完美避开一些功能性的按钮，比如这个事件的坐标值刚好落在一个跳转界面的按钮上，那么在vm恢复运行的时候，会自动跳转界面，站在用户角度来看，就会觉得莫名其妙。<br/>
就算是一个*ACTION_UP*事件，也可能当时用户正在拖拽一样东西，你一个*ACTION_UP*把人家拖拽的东西放下了，所以这个方法还是不太友好，无法保证无感触发。<br/>
还有一个就是，要知道被debug的app不一定是运行在前台，现在模拟触摸事件都是通过[InputManager.injectInputEvent()](http://aosp.app/android-11.0.0_r1/xref/frameworks/base/core/java/android/hardware/input/InputManager.java#896)来传入一个InputEvent，这个方法对应的是display，无法只针对某个应用进行分派，如果此时目标应用运行在后台的话，就接收不到事件了，进一步导致断点不能及时触发。<br/>

提到"后台运行"，"及时触发"，我突然想到了ActivityThread里的Handler.`handleMessage`，这个方法回调频率非常高，Activity的生命周期变化，都要经过这里：比如当app从后台切换到前台，AMS会调用IApplicationThread的`scheduleTransaction`方法，把即将要发生的事件(ResumeActivityItem)通过binder告诉ActivityThread的`mAppThread`，接着`mAppThread`就会向Handler发一条消息。<br/> 
目前看来，把断点打在Handler.`handleMessage`是比较合适的。不过，根据我们平时debug的经验可以得知，如果把断点打在方法上，是会大大影响app的运行效率的，但又不能按行号来打断点，因为各个系统版本的行号都可能有变化。这样排除下来，就只有变量断点(Field Watchpoint)能用了。<br/>
想一下，**Handler.`handleMessage`一定会访问哪个类的哪个成员变量？** <br/>
没错！就是MessageQueue里面的`mMessages`！ watch这个变量之后，只要Handler有消息要处理，就一定会触发。<br/>
但是，Handler总会有空闲的时候，如果在注入时Handler刚好处于空闲状态，断点就不能及时触发，这又回到了刚开始的问题了！ 所以必须在设置好Watchpoint之后，让目标进程的Handler忙起来：<br/>
刚刚提到，Activity每当生命周期发生变化时，都是由AMS跨进程通知ApplicationThread，然后ApplicationThread向Handler发一条消息。<br/>
那么，我们能不能用shell命令模拟键盘事件，比如发送HOME键之类的，间接使目标进程的Activity的生命周期发生变化，而从让ActivityThread的Handler收到消息，进一步触发断点呢？！<br/>
问题又来了，一个正在运行的进程，不一定会启动activity！人家可能只启动了一个service！ 而且，你通过这些命令强行改变了activity的生命周期，比刚开始的模拟触摸事件方案更不友好。<br/>
退一步，**那还有没有其他的命令可以让AMS给ApplicationThread发通知呢？** <br/>
翻了一下源码还真有: 
-  [am crash \<package\>](https://aosp.app/android-11.0.0_r1/xref/frameworks/base/core/java/android/app/ActivityThread.java#1220);
-  [am trace-ipc start](https://aosp.app/android-11.0.0_r1/xref/frameworks/base/core/java/android/app/ActivityThread.java#1689);
-  [am trace-ipc stop --dump-file \<output path\>](https://aosp.app/android-11.0.0_r1/xref/frameworks/base/core/java/android/app/ActivityThread.java#1694);
-  [am profile start \<package\> \<output path\>](https://aosp.app/android-11.0.0_r1/xref/frameworks/base/core/java/android/app/ActivityThread.java#1170);
-  [am profile stop \<package\>](https://aosp.app/android-11.0.0_r1/xref/frameworks/base/core/java/android/app/ActivityThread.java#1170);
-  [am dumpheap \<package\>](https://aosp.app/android-11.0.0_r1/xref/frameworks/base/core/java/android/app/ActivityThread.java#1175);
-  [am attach-agent \<package\> \<agent path\>](https://aosp.app/android-11.0.0_r1/xref/frameworks/base/core/java/android/app/ActivityThread.java#1196);

`am crash`就太暴力了，Handler收到这个消息，会直接抛出一个RemoteServiceException来结束进程。<br/>
中间这几个: `trace-ipc`、`profile`、`dumpheap`都是跟内存/性能分析有关，最后一个`attach-agent`是什么鬼？ 看下处理消息的代码:
```java
    private static boolean attemptAttachAgent(String agent, ClassLoader classLoader) {
        try {
            VMDebug.attachAgent(agent, classLoader);
            return true;
        } catch (IOException e) {
            Slog.e(TAG, "Attaching agent with " + classLoader + " failed: " + agent);
            return false;
        }
    }
```

**妈呀！这不就是加载JVMTI Agent的方法吗？！，am居然还提供了从外部加载的入口！** <br/>
太意外了！我能利用它来做些什么呢？<br/>
于是，就有了这个*agent-injector-for-android*。<br/>

<br/>

### 大致原理：
一句话概括就是：Android中的ActivityManagerService当了内鬼——它对debuggable=true的app提供了一个动态的，即时的加载外部so的入口: attach-agent命令！利用这个命令，可以对debuggable为true的app进行实时的动态注入，不需要重启app。如果设备有安装magisk，还可以通过修改系统属性开启全局调试，实现对release版app的代码注入！

做过Android性能调优的同学应该对agent这个字眼不陌生，通过引入[jvmti.h](https://aosp.app/android-11.0.0_r1/xref/art/openjdkjvmti/include/jvmti.h)可以实现很多高级功能，比如监控每一个class的加载、监控变量和方法的访问，甚至可以监控到每个线程的开始和结束，每一个锁的状态等等。 但我估计很多即使玩过agent的同学，也不知道am还留了一个attach-agent命令，可以随时从外部加载！哈哈哈。<br/>
当agent attach成功之后，会回调[Agent_OnAttach](https://aosp.app/android-11.0.0_r1/xref/art/openjdkjvmti/include/jvmti.h#53)函数，如果在这个时候进一步加载一个外部的dex，那么，这个dex的代码就可以在目标app里面运行了。<br/>

<br/>

### 跟姊妹篇 jdwp-injector-for-android 的区别？
理论上来说，使用attach agent来注入，效率是要高于debugger的，因为debugger其本身也是一个JVMTI Agent，而且debugger的初始化工作，要经过好几轮通讯（获取变量/方法/对象/类的id size，查找目标类/成员变量，设置断点，等待断点触发……）才算完成。

但attach agent方式也有局限性，比如对于系统app必须使用root才能完成注入，还有就是，有的系统app会限制执行data下的文件，针对这种情况，还需要关掉selinux(`setenforce 0`)，而使用debugger来注入的话，只需要满足一个条件：app可调试即可。

<br/>

### 声明：
**此工具仅供学习研究，请勿用于非法用途！**

<br/>

### 感谢：
感谢[小高同学](https://github.com/GaoYuCan)对此工具适配android14提供帮助。

