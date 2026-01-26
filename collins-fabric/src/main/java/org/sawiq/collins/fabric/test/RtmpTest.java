package org.sawiq.collins.fabric.test;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.ffmpeg.global.avutil;

/**
 * Тестовый класс для проверки RTMP поддержки.
 * Запусти его из main() метода или через JUnit.
 */
public class RtmpTest {

    public static void main(String[] args) {
        System.out.println("=== Testing FFmpeg RTMP Support ===");

        // 1. Проверка версии FFmpeg
        try {
            System.out.println("FFmpeg version: " + avutil.av_version_info());
            System.out.println("FFmpeg configuration: " + avutil.avutil_configuration());
        } catch (Exception e) {
            System.err.println("Failed to get FFmpeg version: " + e.getMessage());
        }

        // 2. Список поддерживаемых протоколов
        System.out.println("\n=== Supported protocols ===");
        try {
            String config = avutil.avutil_configuration().getString();
            boolean hasRtmp = config.contains("--enable-librtmp") ||
                    config.contains("rtmp") ||
                    config.contains("protocol");
            System.out.println("RTMP in config: " + hasRtmp);

            // Попытка открыть тестовый RTMP стрим
            testRtmpStream("rtmp://64.188.66.155/live/stream");

        } catch (Exception e) {
            System.err.println("Protocol check failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void testRtmpStream(String url) {
        System.out.println("\n=== Testing RTMP stream: " + url + " ===");

        try {
            avutil.av_log_set_level(avutil.AV_LOG_INFO);
            org.bytedeco.javacv.FFmpegLogCallback.set();

            FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(url);

            // КРИТИЧНО: НЕ вызывать setFormat() для RTMP!
            // grabber.setFormat("flv"); // <-- это включает listen mode

            // RTMP опции (минимальный набор)
            grabber.setOption("rtmp_live", "any");
            grabber.setOption("rtmp_buffer", "1000");
            grabber.setOption("listen", "0"); // явно отключить server mode
            grabber.setOption("tcp_nodelay", "1");
            grabber.setOption("timeout", "10000000");
            grabber.setOption("fflags", "nobuffer");
            grabber.setOption("probesize", "1000000");
            grabber.setOption("analyzeduration", "2000000");

            System.out.println("Opening RTMP stream...");
            grabber.start();

            System.out.println("✓ RTMP stream opened successfully!");
            System.out.println("  Video codec: " + grabber.getVideoCodecName());
            System.out.println("  Audio codec: " + grabber.getAudioCodecName());
            System.out.println("  Video size: " + grabber.getImageWidth() + "x" + grabber.getImageHeight());
            System.out.println("  FPS: " + grabber.getVideoFrameRate());

            grabber.stop();

        } catch (Exception e) {
            System.err.println("✗ RTMP test failed: " + e.getMessage());
            e.printStackTrace();

            // Проверяем конкретную ошибку
            if (e.getMessage().contains("error -10049")) {
                System.err.println("\n⚠️  ERROR -10049 означает что FFmpeg не может подключиться к RTMP серверу.");
                System.err.println("Возможные причины:");
                System.err.println("  1. FFmpeg собран без librtmp (используй GPL версию)");
                System.err.println("  2. RTMP сервер недоступен");
                System.err.println("  3. Неверный URL или stream key");
                System.err.println("\nПопробуй другой тестовый стрим или проверь сборку FFmpeg.");
            }
        }
    }
}