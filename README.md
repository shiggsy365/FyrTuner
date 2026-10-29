# FyrTuner

A live TV app for Fire TV and Android TV that plays channels from Xtream Codes IPTV accounts,
with its own TV guide. Lineups, guides and settings are managed from a web page served by the
app on your TV.

FyrTuner provides no content of its own: you need your own Xtream account.

## Features

- **TV guide:** a grid guide with groups along the top, channel logos, now and next, and a
  preview of the focused channel.
- **Player:** Up and Down change channel within the group. The on-screen panel has Pause,
  Start over, Back to live, a stream picker and closed captions.
- **Several streams per channel:** merge streams for the same channel, from one account or
  several, and FyrTuner falls back to the next stream if one fails or stalls.
- **Catch-up:** on channels whose provider records them, past programmes in the guide can be
  played from the start, for as many days as the provider keeps them.
- **More than one Xtream account,** each with its own name.
- **Web manager** at `http://<tv-address>:8089`:
  - Choose groups, make your own, and move, merge, rename, renumber or remove channels.
  - Upload logos.
  - Add XMLTV guides, link each channel to a guide, set a per-channel guide offset, turn guides
    off and see when each was last refreshed.
  - Clean up channel names.
  - Back up and restore everything.

## Setup

1. Install the APK on your Fire TV or Android TV.
2. Open FyrTuner and enter your Xtream server, username and password, or enter them later on
   the web page.
3. Open the web address the app shows on a phone or computer on the same network, and choose
   your groups and guides.

The guide refreshes in the background, and on demand from the web page.

Anyone on the same network can open the web page, which shows your account details.

## Building

```
cd AndroidTvSampleInput
./gradlew assembleDebug
```

FyrTuner needs Android 5.1 or later.

## Credits

FyrTuner started from Amazon's
[Fire TV Live TV sample app](https://github.com/amzn/ftv-livetv-sample-tv-app), itself based on
Google's sample TV input, and keeps its Apache 2.0 licence (see [LICENSE](LICENSE) and
[NOTICE](NOTICE)). The package name is still the sample's, `com.example.android.sampletvinput`.
Playback uses Amazon's port of ExoPlayer, and the web manager uses NanoHTTPD.
