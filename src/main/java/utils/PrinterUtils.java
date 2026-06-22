package utils;

import com.lambda.module.modules.player.Printer;

public class PrinterUtils
{
    /***
     * setPrinter() enables or disables Lambda client's Printer module. Wrapped so the addon degrades
     * gracefully (returns false) when the Lambda mod isn't installed at runtime.
     * @return true if Lambda is present and the toggle applied
     */
    public static boolean setPrinter(boolean enabled)
    {
        try
        {
            if (Printer.INSTANCE.isEnabled() != enabled)
            {
                if (enabled) Printer.INSTANCE.enable();
                else         Printer.INSTANCE.disable();
            }
            return true;
        }
        catch (Throwable t)
        {
            return false;
        }
    }
}
