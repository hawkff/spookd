/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */


package app.morphe.extension.instagram.entity;

import java.lang.reflect.Method;

import app.morphe.extension.crimera.PikoUtils;

public class DeveloperOptionsItem {

    private long mobileConfigSpecifier;
    private String universalName;
    private String paramName;
    private String universalId;
    private String paramId;

    // getUniversalId(long) runs on MobileConfig flag checks hooked by HookFlags, so the reflection lookups are cached.
    private static volatile Class<?> universalIdHelperClassCache;
    private static volatile Method universalIdMethodCache;

    public DeveloperOptionsItem(long mobileConfigSpecifier, String universalName, String paramName) {
        this.mobileConfigSpecifier = mobileConfigSpecifier;
        this.universalName = universalName;
        this.paramName = paramName;

        this.universalId = this.getUniversalId(mobileConfigSpecifier);
        this.paramId = this.getParamId(mobileConfigSpecifier);
    }

    public DeveloperOptionsItem(long mobileConfigSpecifier) {
        this.mobileConfigSpecifier = mobileConfigSpecifier;
        this.universalName = "";
        this.paramName = "";

        this.universalId = this.getUniversalId(mobileConfigSpecifier);
        this.paramId = this.getParamId(mobileConfigSpecifier);
    }

    private static Class<?> getUniversalIdHelperClass() throws Exception {
        Class<?> cached = universalIdHelperClassCache;
        if (cached != null) return cached;

        Class<?> resolved = Class.forName("X.0B3D");
        universalIdHelperClassCache = resolved;
        return resolved;
    }

    private static Method getUniversalIdMethod() throws Exception {
        Method cached = universalIdMethodCache;
        if (cached != null) return cached;

        Method resolved = getUniversalIdHelperClass().getDeclaredMethod("A00", long.class);
        resolved.setAccessible(true);
        universalIdMethodCache = resolved;
        return resolved;
    }

    public static String getUniversalId(long mobileConfigSpecifier) {
        try {
            int universalId = (int) getUniversalIdMethod().invoke(null, mobileConfigSpecifier);
            return String.valueOf(universalId);
        } catch (Exception e){
            PikoUtils.logger(e);
        }
        return "0";
    }

    public static String getParamId(long mobileConfigSpecifier) {
        try{
            long shifted = mobileConfigSpecifier >>> 16;
            boolean flag = ((mobileConfigSpecifier >>> 62) & 1L) == 1L;
            Object paramId = flag ? (shifted & 0xffff) : (shifted & 0xfff);
            return String.valueOf(paramId);
        } catch (Exception e){
            PikoUtils.logger(e);
        }
        return "0";
    }

    public long getMobileConfigSpecifier() {
        return mobileConfigSpecifier;
    }

    public void setMobileConfigSpecifier(long mobileConfigSpecifier) {
        this.mobileConfigSpecifier = mobileConfigSpecifier;
    }

    public String getUniversalName() {
        return universalName;
    }

    public void setUniversalName(String universalName) {
        this.universalName = universalName;
    }

    public String getParamName() {
        return paramName;
    }

    public void setParamName(String paramName) {
        this.paramName = paramName;
    }

    public String getUniversalId() {
        return universalId;
    }

    public void setUniversalId(String universalId) {
        this.universalId = universalId;
    }

    public String getParamId() {
        return paramId;
    }

    public void setParamId(String paramId) {
        this.paramId = paramId;
    }

    public String getConfigId(){
        return this.getUniversalId() + "::" + this.getParamId();
    }

    @java.lang.Override
    public java.lang.String toString() {
        return "DeveloperOptionsItem{" +
                "mobileConfigSpecifier=" + mobileConfigSpecifier +
                ", universalName='" + universalName + '\'' +
                ", paramName='" + paramName + '\'' +
                ", universalId='" + universalId + '\'' +
                ", paramId='" + paramId + '\'' +
                '}';
    }
}
