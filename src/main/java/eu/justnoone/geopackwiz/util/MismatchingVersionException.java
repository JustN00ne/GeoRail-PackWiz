package eu.justnoone.geopackwiz.util;

public class MismatchingVersionException extends Exception {

    public MismatchingVersionException(String requested, String current) {
        super("\n\n" +
            String.format("Version mismatch: please download and install GeoPackWiz %s (you are running %s).", requested, current) +
            "\n\n"
        );
    }

    public MismatchingVersionException(String message) {
        super(message);
    }
}