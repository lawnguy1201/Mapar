package utils;

public class MathUtils
{
    public static double getSquaredDistance(double x, double y, double z, double staticX, double staticY, double staticZ)
    {
        double a = x - staticX;
        double b = y - staticY;
        double c = z - staticZ;

        return a * a + b * b + c * c;

    }
}
