# Majid Browser v1.1

Android Native Kotlin + Android WebView ব্রাউজার (প্যাকেজ `com.majid.browser`)।

## v1.1-এ কী বদলেছে
### বাগ ফিক্স
- Android 15 edge-to-edge: status/navigation bar ও keyboard-এর জন্য insets padding যোগ।
- Incognito এখন প্রতি tab-এর আলাদা প্রপার্টি (আগে গ্লোবাল ফ্ল্যাগ ছিল, সব tab-কে প্রভাবিত করত)।
- Background tab আর address bar/title ওভাররাইট করে না।
- Desktop site toggle এখন আসল desktop User-Agent ব্যবহার করে, প্রতি tab-এ আলাদা।
- Download: Cookie ও Referer header পাঠানো হয় (login লাগে এমন ফাইল), Android 9-এর নিচে storage permission।
- `http://` বা SSL ত্রুটিতে স্পষ্ট error page ও "Try again" লিংক।
- নিরাপত্তা: `file:`/`content:`/`javascript:` ব্লক; `intent://` শুধু user gesture-এ ও fallback URL সহ; WebView-এ file access বন্ধ।
- Home বাটন এখন বর্তমান tab-এ হোম পেজ লোড করে; tab বন্ধ করা নিরাপদ।

### নতুন ফিচার
- Loading progress bar এবং reload ↔ stop বাটন
- ফাইল আপলোড (`<input type=file>`)
- ফুলস্ক্রিন ভিডিও
- Camera / Mic / Location permission প্রম্পট (সাইট অনুযায়ী Allow/Block)
- Tab switcher: প্রতিটি tab-এর পাশে ✕ বাটন, Incognito tab খোলার বাটন
- Bookmark/History-তে পেজের নাম সহ দেখায়; লং-প্রেসে একটি করে মুছুন; ☆/★ টগল
- Search engine বেছে নেওয়া (Google / DuckDuckGo / Bing)
- অন্য অ্যাপ থেকে http/https লিংক Majid Browser-এ খোলা (default browser হিসেবে বাছাই করা যায়)
- "Clear browsing data": history, cache, cookies, site data (bookmark থাকে)

## Build requirements
- Android Studio, JDK 17, Android SDK Platform 35, Gradle 8.9 (AGP 8.7.3)

## Build (Android Studio)
1. ZIP extract করে project root Open করুন, Gradle sync হতে দিন।
2. Build > Build APK(s)। APK: `app/build/outputs/apk/debug/app-debug.apk`

## ফোন থেকে ফ্রি APK build (GitHub Actions)
1. GitHub-এ নতুন repository বানান, সব ফাইল upload করুন (root-এ `settings.gradle.kts` থাকবে)।
2. `Actions` > `Build Majid Browser APK` > `Run workflow`।
3. শেষ হলে `Artifacts` থেকে `Majid-Browser-debug-apk` ডাউনলোড করুন।

## সীমাবদ্ধতা
- এটি Chrome নয়, Android System WebView ব্যবহার করে।
- Incognito "basic": আলাদা tab, no-cache, DOM storage বন্ধ, history সেভ হয় না, third-party cookie বন্ধ। কিন্তু WebView-তে cookie jar সবার জন্য একটাই, তাই cookie normal tab-এর সাথে শেয়ার হয়। পূর্ণ privacy গ্যারান্টি নয়।
- `blob:` ও `data:` ডাউনলোড সমর্থিত নয়।
- Tab-এর সেশন অ্যাপ বন্ধ হলে সংরক্ষিত হয় না।
- এই v1.1 কোড আমি build করে চালাতে পারিনি (Android SDK ছিল না); প্রথম build-এ কোনো compile error এলে error টা পাঠান।
