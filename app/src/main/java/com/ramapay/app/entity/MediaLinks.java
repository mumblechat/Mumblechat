package com.ramapay.app.entity;

import android.content.Context;
import android.text.TextUtils;

public class MediaLinks
{
    // Update these media platform links and ids to target your media groups,
    // then update the MEDIA_TARGET_APPLICATION to match your applicationId
    public static final String MEDIA_TARGET_APPLICATION = "com.ramapay.wallet";
    public static final String AWALLET_DISCORD_URL = null;
    public static final String AWALLET_TWITTER_ID = "twitter://user?user_id=ramestta";
    public static final String AWALLET_FACEBOOK_ID = "fb://page/Ramestta";
    public static final String AWALLET_TWITTER_URL = "https://x.com/ramestta";
    public static final String AWALLET_FACEBOOK_URL = "https://www.facebook.com/Ramestta/";
    public static final String AWALLET_LINKEDIN_URL = null;
    public static final String AWALLET_REDDIT_URL = null;
    public static final String AWALLET_INSTAGRAM_URL = "https://www.instagram.com/ramestta/";
    public static final String AWALLET_BLOG_URL = "https://ramestta.com/blog";
    public static final String AWALLET_FAQ_URL = "https://ramestta.com/faq";
    public static final String AWALLET_GITHUB = "https://github.com/Ramestta-Blockchain/";
    public static final String AWALLET_EMAIL1 = "support";
    public static final String AWALLET_EMAIL2 = "ramestta.com";
    public static final String AWALLET_SUBJECT = "RamaPay Support";

    public static boolean isMediaTargeted(Context context)
    {
        if (!TextUtils.isEmpty(MEDIA_TARGET_APPLICATION))
        {
            return context.getPackageName().equals(MEDIA_TARGET_APPLICATION);
        }
        else
        {
            return false;
        }
    }
}
