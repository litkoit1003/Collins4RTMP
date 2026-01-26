package org.sawiq.collins.fabric.test;

import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;

public class RtmpAlternativeTest {

    public static void main(String[] args) {
        testRtmpDirect("rtmp://64.188.66.155/live/stream");
    }

    private static void testRtmpDirect(String url) {
        System.out.println("=== Testing RTMP (alternative method): " + url + " ===");

        try {
            avutil.av_log_set_level(avutil.AV_LOG_DEBUG); // полные логи
            org.bytedeco.javacv.FFmpegLogCallback.set();

            // Метод 1: Без опций вообще
            System.out.println("\n--- Method 1: No options ---");
            try {
                FFmpegFrameGrabber g1 = new FFmpegFrameGrabber(url);
                g1.start();
                System.out.println("✓ Method 1 SUCCESS!");
                System.out.println("  Size: " + g1.getImageWidth() + "x" + g1.getImageHeight());
                g1.stop();
            } catch (Exception e) {
                System.err.println("✗ Method 1 failed: " + e.getMessage());
            }

            // Метод 2: Только timeout
            System.out.println("\n--- Method 2: Only timeout ---");
            try {
                FFmpegFrameGrabber g2 = new FFmpegFrameGrabber(url);
                g2.setOption("timeout", "10000000");
                g2.start();
                System.out.println("✓ Method 2 SUCCESS!");
                g2.stop();
            } catch (Exception e) {
                System.err.println("✗ Method 2 failed: " + e.getMessage());
            }

            // Метод 3: Минимальные опции
            System.out.println("\n--- Method 3: Minimal options ---");
            try {
                FFmpegFrameGrabber g3 = new FFmpegFrameGrabber(url);
                g3.setOption("rtmp_live", "any");
                g3.setOption("listen", "0");
                g3.start();
                System.out.println("✓ Method 3 SUCCESS!");
                g3.stop();
            } catch (Exception e) {
                System.err.println("✗ Method 3 failed: " + e.getMessage());
            }

            // Метод 4: VLC-подобные опции
            System.out.println("\n--- Method 4: VLC-like options ---");
            try {
                FFmpegFrameGrabber g4 = new FFmpegFrameGrabber(url);
                g4.setOption("rtmp_app", "live"); // app name
                g4.setOption("rtmp_playpath", "stream"); // stream name
                g4.setOption("rtmp_live", "1");
                g4.setOption("rtmp_buffer", "3000");
                g4.start();
                System.out.println("✓ Method 4 SUCCESS!");
                g4.stop();
            } catch (Exception e) {
                System.err.println("✗ Method 4 failed: " + e.getMessage());
            }

        } catch (Exception e) {
            System.err.println("Test failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}