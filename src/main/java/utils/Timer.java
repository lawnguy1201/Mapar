package utils;
// Made By bebeli555
// modified by lawnguy 1/15/26

public class Timer {
    public long ms;

    public Timer() {
        ms = 0;
    }

    public boolean hasPassed(int ms) {
        return System.currentTimeMillis() - this.ms >= ms;
    }

    public boolean hasPassedDouble(double ms) {
        return System.currentTimeMillis() - this.ms >= ms;
    }

    public boolean hasPassedLong(long ms) {
        return System.currentTimeMillis() - this.ms >= ms;
    }

    public void reset() {
        ms = System.currentTimeMillis();
    }
}
