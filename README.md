# Countdown (Android app + home-screen widget)

Counts down to a date and time you pick, shown as **hours : minutes : seconds** only (no days, so 3 days shows as 72:00:00).

## Put it on your phone

1. Install **Android Studio** (free): https://developer.android.com/studio
2. Unzip this folder, open Android Studio, choose **Open** and select the `CountdownApp` folder.
   Wait for it to finish "syncing" (the first time it downloads what it needs, which takes a few minutes).
3. On your phone, turn on **Developer options** and **USB debugging**:
   Settings → About phone → tap **Build number** 7 times, then Settings → System → Developer options → USB debugging.
4. Plug your phone into the computer, allow the prompt on the phone, and press the green **Run ▶** button in Android Studio.
   The app installs and opens on your phone, and stays installed after you unplug.

**No cable?** Use Build → Build App Bundle(s) / APK(s) → **Build APK(s)**, click "locate", copy `app-debug.apk`
to your phone (email, Google Drive, etc.), open it and allow "install unknown apps" when asked.

## Using it

- Type a name, tap **Pick date & time**, then **Save countdown**.
- Tap **Add widget to home screen**, or long-press your home screen → Widgets → Countdown.
- The widget can be resized, and tapping it opens the app.

Notes: when less than an hour is left, the widget shows MM:SS (Android's built-in widget timer drops the zero hours); the app always shows HH:MM:SS.
