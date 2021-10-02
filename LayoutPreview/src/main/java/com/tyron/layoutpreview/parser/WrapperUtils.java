package com.tyron.layoutpreview.parser;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ViewTypeParser;
import com.flipkart.android.proteus.processor.AttributeProcessor;
import com.flipkart.android.proteus.processor.EnumProcessor;
import com.flipkart.android.proteus.processor.StringAttributeProcessor;
import com.tyron.layoutpreview.model.Attribute;
import com.tyron.layoutpreview.model.Format;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

public class WrapperUtils {

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void addEnumProcessors(ViewTypeParser processor, Attribute attribute, Method method, Object[] params) {
        List<Format> formats = attribute.getFormats();
        int offset = attribute.getXmlParameterOffset();
        if (formats.contains(Format.ENUM) && formats.contains(Format.REFERENCE)) {
            processor.addAttributeProcessor(attribute.getXmlName(), new EnumProcessor<ViewGroup>(attribute.getEnumValues()) {
                @Override
                public void apply(ViewGroup view, int value) {
                    params[offset] = value;
                    set(attribute, view, getParameters(attribute.getParameters()), params);
                }

                @Override
                public void applyOther(ViewGroup view, String value) {
                    params[offset] = (((ProteusContext) view.getContext())).getInflater()
                            .getUniqueViewId(value.replace("@+id/", "".replace("@id/", "")));
                    set(attribute, view, getParameters(attribute.getParameters()), params);
                }
            });
        }
    }

    private static void set(Attribute attribute, View view, Class<?>[] classParams, Object[] params) {
        if (attribute.isLayoutParams()) {
            if (view.getLayoutParams().getClass().getName().equals(attribute.getLayoutParamsClass())) {
                ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
                try {
                    Field field = layoutParams.getClass().getDeclaredField(attribute.getMethodName());
                    field.set(layoutParams, params[attribute.getXmlParameterOffset()]);
                } catch (NoSuchFieldException | IllegalAccessException e) {
                    Log.e("WrapperUtils", "Unable to set layout params: ", e);
                }
            }
        } else {
            invokeMethod(view, getMethod(view.getClass(), attribute.getMethodName(), classParams), params);
        }
    }

    public static void invokeMethod(Object object, Method method, Object[] values) {
        try {
            method.invoke(object, values);
        } catch (Exception e) {
            Log.w("CustomView", "Unable to set attribute " + Log.getStackTraceString(e));
        }
    }

    /**
     * Retrieves the method from the current class, if not found tries to find it in the parent class.
     *
     * @return null if method is not found
     */
    public static Method getMethod(Class<? extends View> clazz, String name, Class<?>[] parameters) {
        Method method = null;
        try {
            method = clazz.getDeclaredMethod(name, parameters);
        } catch (Exception ignore) {

        }

        if (method == null) {
            try {
                method = clazz.getMethod(name, parameters);
            } catch (Exception ignore) {

            }
        }

        return method;
    }

    private static final Class<?>[] mPrimitives = new Class<?>[] {int.class, short.class, long.class, float.class, byte.class, double.class, char.class};
    /*
     * Converts an array of fully qualified names to objects
     * @return null if an error has occurred
     */
    public static Class<?>[] getParameters(String[] parameters) {
        Class<?>[] params = new Class<?>[parameters.length];
        outer: for (int i = 0; i < parameters.length; i++) {

            // Try first if this class is a primitive class
            for (Class<?> primitive : mPrimitives) {
                if (primitive.getName().equals(parameters[i])) {
                    params[i] = primitive;
                    continue outer;
                }
            }

            try {
                params[i] = Class.forName(parameters[i]);
            } catch (ClassNotFoundException e) {
                Log.w("CustomView", "Unable to find class " + parameters[i]);
                return null;
            }
        }
        return params;
    }
}
