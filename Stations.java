package com.muse.retrocassette;

public class Stations {
    public static class Station {
        public final String name;
        public final String desc;
        public final String url;
        public Station(String name, String desc, String url) {
            this.name = name;
            this.desc = desc;
            this.url = url;
        }
        @Override public String toString() { return name + " - " + desc; }
    }

    // Christian stations. No sample / placeholder streams.
    // All URLs below returned HTTP 200 when checked 2026-11-01/02 from this VM.
    public static final Station[] ALL = new Station[] {
        new Station("BRIGHT-FM, WRBS-FM 95.1 FM, Baltimore, MD", "Christian adult contemporary - USA",
            "https://wrbs-hls.streamguys1.com/Shine-FM"),
        new Station("K-LOVE", "Contemporary Christian - USA (EMF)",
            "https://maestro.emfcdn.com/stream_for/klove/tunein/aac"),
        new Station("Air1", "Christian worship and hits - USA (EMF)",
            "https://maestro.emfcdn.com/stream_for/air1/tunein/aac"),
        new Station("K-LOVE 90s", "90s Christian hits - USA (EMF)",
            "https://maestro.emfcdn.com/stream_for/k-love-90s/airable/aac"),
        new Station("Moody Radio WMBI 90.1 FM, Chicago", "Christian talk and teaching - USA",
            "https://14123.live.streamtheworld.com/WMBIFM.mp3"),
        new Station("Moody Radio Northwest KMBI", "Christian radio - USA",
            "https://playerservices.streamtheworld.com/api/livestream-redirect/KMBIFM.mp3"),
        new Station("Family Life Radio", "Christian music - USA",
            "https://icecast.streammyflr.org/FLRstream"),
        new Station("American Family Radio - Music", "Christian music - USA",
            "https://mediaserver3.afa.net:8443/music.mp3"),
        new Station("American Family Radio - Talk", "Christian talk - USA",
            "https://mediaserver3.afa.net:8443/talk.mp3"),
        new Station("His Radio", "Christian music - USA",
            "https://rtn.cdnstream1.com/2566_96.aac"),
        new Station("HIS Radio Praise", "Christian praise - USA",
            "https://rtn.cdnstream1.com/2568_96.aac"),
        new Station("Relevant Radio", "Catholic radio - USA",
            "https://playerservices.streamtheworld.com/api/livestream-redirect/RR_MAIN.mp3"),
        new Station("Bible Broadcasting Network English", "Bible teaching and music - USA",
            "https://streams.radiomast.io/844b0a81-f4b9-485e-adaa-aab8d3ea9f7f"),
        new Station("Abiding Radio - Instrumental", "Instrumental worship - USA",
            "https://streams.abidingradio.com/instrumental"),
        new Station("Christian Life Radio", "Christian music and teaching - USA",
            "https://ice64.securenetsystems.net/CLR1MP3"),
        // Added for GX-880 presets (P1 in the screenshot). Verified HTTP 200 audio/aacp via Radio Browser.
        new Station("WGTS 91.9 FM, Washington, D.C.", "Christian Contemporary & Uplifting - USA",
            "https://wgts-live-a.edge.audiocdn.com/10320"),
        // Added 2026-11-01 at user request: FEBC Korea - Seoul FM.
        // Fixed 2026-11-01 in 1.9: the :1935 https URL did not respond
        // (no HTTP response, likely blocked / not listening on 1935 for
        // https), so FEBC never played. The no-port https URL, same as
        // the web player, returns a live HLS master playlist
        // #EXTM3U, BANDWIDTH=66847, CODECS="mp4a.40.2".
        new Station("FEBC Korea - Seoul FM 106.9, Seoul, South Korea", "Christian - Korea (HLKX-FM)",
            "https://mlive2.febc.net/live/seoulfm/playlist.m3u8"),
    };
}
