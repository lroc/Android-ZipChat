package com.kdoherty.zipchat.views;

import android.graphics.Bitmap;
import android.view.View;
import android.widget.ImageView;

import com.nostra13.universalimageloader.core.display.FadeInBitmapDisplayer;
import com.nostra13.universalimageloader.core.listener.SimpleImageLoadingListener;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public class AnimateFirstDisplayListener extends SimpleImageLoadingListener {

    static final List<String> sDisplayedImages = Collections.synchronizedList(new LinkedList<String>());

    public static void clearImages() {
        sDisplayedImages.clear();
    }

    @Override
    public void onLoadingComplete(String imageUri, View view, Bitmap loadedImage) {
        if (loadedImage != null) {
            ImageView imageView = (ImageView) view;
            boolean firstDisplay = !sDisplayedImages.contains(imageUri);
            if (firstDisplay) {
                FadeInBitmapDisplayer.animate(imageView, 500);
                sDisplayedImages.add(imageUri);
            }
        }
    }
}