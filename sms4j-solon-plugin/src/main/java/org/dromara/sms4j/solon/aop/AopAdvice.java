package org.dromara.sms4j.solon.aop;

import lombok.extern.slf4j.Slf4j;
import org.dromara.sms4j.comm.config.SmsConfig;
import org.dromara.sms4j.comm.exception.SmsBlendException;
import org.dromara.sms4j.comm.factory.BeanFactory;
import org.dromara.sms4j.comm.utils.SmsUtil;
import org.dromara.sms4j.comm.utils.TimeExpiredPoolCache;
import org.dromara.sms4j.solon.utils.RedisUtils;
import org.noear.solon.core.AopContext;
import org.noear.solon.core.aspect.Interceptor;
import org.noear.solon.core.aspect.Invocation;

import java.util.ArrayList;

@Slf4j
public class AopAdvice implements Interceptor {
    private static final Long minTimer = 60 * 1000L;
    private static final Long accTimer = 24 * 60 * 60 * 1000L;

    private static final String REDIS_KEY = "sms:restricted:";

    private SmsConfig config;

    private RedisUtils redis;

    public AopAdvice(AopContext context){
        context.getBeanAsync(SmsConfig.class, bean->{
            config = bean;
        });

        context.getBeanAsync(RedisUtils.class, bean->{
            redis = bean;
        });
    }


    @Override
    public Object doIntercept(Invocation inv) throws Throwable {
        if (BeanFactory.getSmsConfig().getRestricted()) {
            String args = "";
            ArrayList<String> argsList = new ArrayList<>();
            try {
                args = (String) inv.args()[0];
            } catch (Exception e) {
                for (Object o : (ArrayList<?>) inv.args()[0]) {
                    argsList.add((String) o);
                }
            }
            SmsBlendException process = redisProcess(args);
            if (process != null) {
                throw process;
            }
            for (String f : argsList) {
                SmsBlendException proce = null;
                try {
                    proce = redisProcess(f);
                } catch (Exception e) {
                    log.error(e.getMessage());
                    throw new RuntimeException(e);
                }
                if (proce != null) {
                    throw proce;
                }
            }
        }

        return inv.invoke();
    }


    private SmsBlendException process(String args) throws Exception {
        TimeExpiredPoolCache instance = TimeExpiredPoolCache.getInstance();//缓存实例
        Integer accountMax = config.getAccountMax();//每日最大发送量
        Integer minuteMax = config.getMinuteMax();//每分钟最大发送量
        if (SmsUtil.isNotEmpty(accountMax)) {   //是否配置了每日限制
            Integer i = instance.get(args + "max");
            if (SmsUtil.isEmpty(i)) {
                instance.put(args + "max", 1, accTimer);
            } else if (i > accountMax) {
                log.info("The phone:"+args +",number of short messages reached the maximum today");
                return new SmsBlendException("The phone:"+args +",number of short messages reached the maximum today");
            } else {
                instance.put(args + "max", i + 1, accTimer);
            }
        }
        if (SmsUtil.isNotEmpty(minuteMax)) {  //是否配置了每分钟最大限制
            Integer o = instance.get(args);
            if (SmsUtil.isNotEmpty(o)) {
                if (o < minuteMax) {
                    instance.put(args, o + 1, minTimer);
                } else {
                    log.info("The phone:"+args +",number of short messages reached the maximum today");
                    return new SmsBlendException("The phone:", args + " Text messages are sent too often！");
                }
            } else {
                instance.put(args, 1, minTimer);
            }
        }
        return null;
    }

    private SmsBlendException redisProcess(String args) throws Exception{
        if (config.getRedisCache().equals("false")){
            return process(args);
        }

        Integer accountMax = config.getAccountMax();//每日最大发送量
        Integer minuteMax = config.getMinuteMax();//每分钟最大发送量
        if (SmsUtil.isNotEmpty(accountMax)) {   //是否配置了每日限制
            Integer i = (Integer) redis.getByKey(REDIS_KEY+args + "max");
            if (SmsUtil.isEmpty(i)) {
                redis.setOrTime(REDIS_KEY+args + "max", 1,accTimer/1000);
            } else if (i > accountMax) {
                log.info("The phone:"+args +",number of short messages reached the maximum today");
                return new SmsBlendException("The phone:"+args +",number of short messages reached the maximum today");
            } else {
                redis.setOrTime(REDIS_KEY+args + "max", i + 1,accTimer/1000);
            }
        }
        if (SmsUtil.isNotEmpty(minuteMax)) {  //是否配置了每分钟最大限制
            Integer o = (Integer) redis.getByKey(REDIS_KEY+args);
            if (SmsUtil.isNotEmpty(o)) {
                if (o < minuteMax) {
                    redis.setOrTime(REDIS_KEY+args, o + 1,minTimer/1000);
                } else {
                    log.info("The phone:"+args +",number of short messages reached the maximum today");
                    return new SmsBlendException("The phone:", args + " Text messages are sent too often！");
                }
            } else {
                redis.setOrTime(REDIS_KEY+args, 1,minTimer/1000);
            }
        }
        return null;
    }
}
