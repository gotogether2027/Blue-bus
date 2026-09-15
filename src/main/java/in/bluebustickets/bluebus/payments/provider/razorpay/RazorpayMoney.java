package in.bluebustickets.bluebus.payments.provider.razorpay;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class RazorpayMoney {

    private RazorpayMoney() {
    }

    static long toMinorUnits(BigDecimal amount, String currency) {
        if (amount == null || currency == null || !"INR".equalsIgnoreCase(currency.trim())) {
            throw new IllegalArgumentException("Razorpay currently supports INR amounts only.");
        }
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("Payment amount cannot be negative.");
        }
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY)
                    .movePointRight(2)
                    .longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Payment amount must have at most two decimal places.");
        }
    }

    static BigDecimal fromMinorUnits(long minorUnits, String currency) {
        if (currency == null || !"INR".equalsIgnoreCase(currency.trim())) {
            throw new IllegalArgumentException("Razorpay currently supports INR amounts only.");
        }
        return BigDecimal.valueOf(minorUnits, 2);
    }
}
