package me.xiaobailong24.mvvmarms.weather.app;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.net.ParseException;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;

import com.google.gson.JsonIOException;
import com.google.gson.JsonParseException;
import com.squareup.leakcanary.LeakCanary;
import com.squareup.leakcanary.RefWatcher;

import org.json.JSONException;

import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import me.xiaobailong24.mvvmarms.base.delegate.App;
import me.xiaobailong24.mvvmarms.base.delegate.AppLifecycles;
import me.xiaobailong24.mvvmarms.di.module.GlobalConfigModule;
import me.xiaobailong24.mvvmarms.http.GlobalHttpHandler;
import me.xiaobailong24.mvvmarms.http.RequestInterceptor;
import me.xiaobailong24.mvvmarms.repository.ConfigModule;
import me.xiaobailong24.mvvmarms.utils.UiUtils;
import me.xiaobailong24.mvvmarms.weather.BuildConfig;
import me.xiaobailong24.mvvmarms.weather.mvvm.model.api.Api;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import retrofit2.HttpException;
import timber.log.Timber;

/**
 * Created by xiaobailong24 on 2017/7/24.
 * app的全局配置信息在此配置,需要将此实现类声明到AndroidManifest中
 */
public class GlobalConfiguration implements ConfigModule {
    @Override
    public void applyOptions(Context context, GlobalConfigModule.Builder builder) {
        if (!BuildConfig.LOG_DEBUG) //Release 时,让框架不再打印 Http 请求和响应的信息
            builder.printHttpLogLevel(RequestInterceptor.Level.NONE);

        builder.baseUrl(Api.APP_DOMAIN)
                .globalHttpHandler(new GlobalHttpHandler() {// 这里可以提供一个全局处理Http请求和响应结果的处理类,
                    // 这里可以比客户端提前一步拿到服务器返回的结果,可以做一些操作,比如token超时,重新获取
                    @Override
                    public Response onHttpResultResponse(String httpResult, Interceptor.Chain chain, Response response) {
                        /* 这里可以先客户端一步拿到每一次http请求的结果,可以解析成json,做一些操作,如检测到token过期后
                           重新请求token,并重新执行请求 */

                     /* 这里如果发现token过期,可以先请求最新的token,然后在拿新的token放入request里去重新请求
                        注意在这个回调之前已经调用过proceed,所以这里必须自己去建立网络请求,如使用okhttp使用新的request去请求
                        create a new request and modify it accordingly using the new token
                        Request newRequest = chain.request().newBuilder().header("token", newToken)
                                             .build();

                        retry the request

                        response.body().close();
                        如果使用okhttp将新的请求,请求成功后,将返回的response  return出去即可
                        如果不需要返回新的结果,则直接把response参数返回出去 */

                        return response;
                    }

                    // 这里可以在请求服务器之前可以拿到request,做一些操作比如给request统一添加token或者header以及参数加密等操作
                    @Override
                    public Request onHttpRequestBefore(Interceptor.Chain chain, Request request) {
                        /* 如果需要再请求服务器之前做一些操作,则重新返回一个做过操作的的request如增加header,
                        不做操作则直接返回request参数*/
                        return chain.request().newBuilder()
                                .addHeader("Connection", "close")
                                .build();
                        //                        return request;
                    }
                })
                .responseErrorListener((context1, t) -> {
                    /* 用来提供处理所有错误的监听
                       rxjava必要要使用ErrorHandleSubscriber(默认实现Subscriber的onError方法),此监听才生效 */
                    Timber.tag("Catch-Error").w(t.getMessage());
                    //这里不光是只能打印错误,还可以根据不同的错误作出不同的逻辑处理
                    String msg = "未知错误";
                    if (t instanceof UnknownHostException) {
                        msg = "网络不可用";
                    } else if (t instanceof SocketTimeoutException) {
                        msg = "请求网络超时";
                    } else if (t instanceof HttpException) {
                        HttpException httpException = (HttpException) t;
                        msg = convertStatusCode(httpException);
                    } else if (t instanceof JsonParseException || t instanceof ParseException || t instanceof JSONException || t instanceof JsonIOException) {
                        msg = "数据解析错误";
                    }
                    UiUtils.snackbarText(msg);
                })
                .gsonConfiguration((context1, gsonBuilder) -> {//这里可以自己自定义配置Gson的参数
                    gsonBuilder
                            .serializeNulls()//支持序列化null的参数
                            .enableComplexMapKeySerialization();//支持将序列化key为object的map,默认只能序列化key为string的map
                })
                .retrofitConfiguration((context1, retrofitBuilder) -> {
                    //这里可以自己自定义配置Retrofit的参数,甚至你可以替换系统配置好的okhttp对象
                    // retrofitBuilder.addConverterFactory(FastJsonConverterFactory.create());//比如使用fastjson替代gson
                })
                .okhttpConfiguration((context1, okhttpBuilder) -> {
                    //这里可以自己自定义配置Okhttp的参数
                    okhttpBuilder.writeTimeout(10, TimeUnit.SECONDS);
                });
    }

    @Override
    public void injectAppLifecycle(Context context, List<AppLifecycles> lifecycles) {
        // AppDelegate.Lifecycle 的所有方法都会在基类Application对应的生命周期中被调用,所以在对应的方法中可以扩展一些自己需要的逻辑
        lifecycles.add(new AppLifecycles() {

            @Override
            public void attachBaseContext(Context base) {
                //                MultiDex.install(base);  //这里比 onCreate 先执行,常用于 MultiDex 初始化,插件化框架的初始化
            }

            @Override
            public void onCreate(Application application) {
                if (BuildConfig.LOG_DEBUG) {//Timber初始化
                    //Timber 是一个日志框架容器,外部使用统一的Api,内部可以动态的切换成任何日志框架(打印策略)进行日志打印
                    //并且支持添加多个日志框架(打印策略),做到外部调用一次 Api,内部却可以做到同时使用多个策略
                    //比如添加三个策略,一个打印日志,一个将日志保存本地,一个将日志上传服务器
                    Timber.plant(new Timber.DebugTree());
                    // 如果你想将框架切换为 Logger 来打印日志,请使用下面的代码,如想切换为其他日志框架请根据下面的方式扩展
                    //                    Logger.addLogAdapter(new AndroidLogAdapter());
                    //                    Timber.plant(new Timber.DebugTree() {
                    //                        @Override
                    //                        protected void log(int priority, String tag, String message, Throwable t) {
                    //                            Logger.log(priority, tag, message, t);
                    //                        }
                    //                    });
                }
                //leakCanary内存泄露检查
                ((App) application).getArmsComponent()
                        .extras()
                        .put(RefWatcher.class.getName(), BuildConfig.USE_CANARY ? LeakCanary.install(application) : RefWatcher.DISABLED);
            }

            @Override
            public void onTerminate(Application application) {

            }
        });
    }

    @Override
    public void injectActivityLifecycle(Context context, List<Application.ActivityLifecycleCallbacks> lifecycles) {
        lifecycles.add(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            }

            @Override
            public void onActivityStarted(Activity activity) {
            }

            @Override
            public void onActivityResumed(Activity activity) {
            }

            @Override
            public void onActivityPaused(Activity activity) {
            }

            @Override
            public void onActivityStopped(Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
            }
        });
    }

    @Override
    public void injectFragmentLifecycle(Context context, List<FragmentManager.FragmentLifecycleCallbacks> lifecycles) {
        lifecycles.add(new FragmentManager.FragmentLifecycleCallbacks() {

            @Override
            public void onFragmentCreated(FragmentManager fm, Fragment f, Bundle savedInstanceState) {
                // 在配置变化的时候将这个 Fragment 保存下来,在 Activity 由于配置变化重建是重复利用已经创建的Fragment。
                //Activity重建时，恢复 Fragment; onCreate() 和 onDestroy() 不会被调用
                // https://developer.android.com/reference/android/app/Fragment.html?hl=zh-cn#setRetainInstance(boolean)
                // 如果在 XML 中使用 <Fragment/> 标签,的方式创建 Fragment 请务必在标签中加上 android:id 或者 android:tag 属性,否则 setRetainInstance(true) 无效
                // 在 Activity 中绑定少量的 Fragment 建议这样做,如果需要绑定较多的 Fragment 不建议设置此参数,如 ViewPager 需要展示较多 Fragment
                f.setRetainInstance(true);
            }

            @Override
            public void onFragmentDestroyed(FragmentManager fm, Fragment f) {
                //这里应该是检测 Fragment 而不是 FragmentLifecycleCallbacks 的泄露。
                ((RefWatcher) ((App) f.getActivity()
                        .getApplication())
                        .getArmsComponent()
                        .extras()
                        .get(RefWatcher.class.getName()))
                        .watch(f);
            }
        });
    }


    private String convertStatusCode(HttpException httpException) {
        String msg;
        if (httpException.code() == 500) {
            msg = "服务器发生错误";
        } else if (httpException.code() == 404) {
            msg = "请求地址不存在";
        } else if (httpException.code() == 403) {
            msg = "请求被服务器拒绝";
        } else if (httpException.code() == 307) {
            msg = "请求被重定向到其他页面";
        } else {
            msg = httpException.message();
        }
        return msg;
    }

}
