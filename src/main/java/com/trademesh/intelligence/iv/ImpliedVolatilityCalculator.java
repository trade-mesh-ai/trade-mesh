package com.trademesh.intelligence.iv;

/**
 * Calculates implied volatility using Newton-Raphson method on Black-Scholes model.
 * This utility is necessary because Kite REST API does not provide IV for options.
 * IV is calculated locally by solving the Black-Scholes equation for sigma.
 */
public class ImpliedVolatilityCalculator {

    private static final double RISK_FREE_RATE = 0.065; // RBI repo rate ~6.5%
    private static final int MAX_ITERATIONS = 100;
    private static final double PRECISION = 1e-5;
    private static final double SIGMA_MAX_CAP = 5.0; // 500% volatility cap

    /**
     * Calculates implied volatility for an option using Newton-Raphson iteration.
     *
     * @param spotPrice         the current spot price of the underlying
     * @param strikePrice       the strike price of the option
     * @param timeToExpiryYears time to expiry in years (must be positive)
     * @param optionPrice       the current market price of the option
     * @param optionType        "CE" for call option, "PE" for put option
     * @return implied volatility as a percentage (e.g., 18.45 for 18.45%), or 0.0 if calculation fails
     */
    public static double calculate(double spotPrice, double strikePrice,
                                   double timeToExpiryYears, double optionPrice,
                                   String optionType) {
        // Validate inputs
        if (timeToExpiryYears <= 0 || optionPrice <= 0) {
            return 0.0;
        }

        // Initial guess: 30% volatility
        double sigma = 0.3;

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            // Sanity check: sigma must be positive and within reasonable bounds
            if (sigma <= 0 || sigma > SIGMA_MAX_CAP) {
                return 0.0;
            }

            // Calculate Black-Scholes price at current sigma
            double price = blackScholes(spotPrice, strikePrice, timeToExpiryYears,
                    RISK_FREE_RATE, sigma, optionType);

            // Calculate vega (sensitivity of price to sigma)
            double vega = vega(spotPrice, strikePrice, timeToExpiryYears,
                    RISK_FREE_RATE, sigma);

            // Check for convergence
            if (vega < 1e-10) {
                break;
            }

            // Newton-Raphson update: sigma_new = sigma - (price - optionPrice) / vega
            double diff = price - optionPrice;
            if (Math.abs(diff) < PRECISION) {
                break;
            }

            sigma -= diff / vega;
        }

        // Final sanity check
        if (sigma <= 0 || sigma > SIGMA_MAX_CAP) {
            return 0.0;
        }

        // Return as percentage (e.g., 0.1845 becomes 18.45)
        return Math.round(sigma * 10000.0) / 100.0;
    }

    /**
     * Black-Scholes option pricing formula.
     *
     * @param S     spot price
     * @param K     strike price
     * @param T     time to expiry in years
     * @param r     risk-free rate
     * @param sigma volatility
     * @param type  "CE" for call, "PE" for put
     * @return option price
     */
    private static double blackScholes(double S, double K, double T,
                                       double r, double sigma, String type) {
        double d1 = (Math.log(S / K) + (r + 0.5 * sigma * sigma) * T)
                / (sigma * Math.sqrt(T));
        double d2 = d1 - sigma * Math.sqrt(T);

        if ("CE".equals(type)) {
            // Call option: C = S * N(d1) - K * e^(-rT) * N(d2)
            return S * norm(d1) - K * Math.exp(-r * T) * norm(d2);
        } else {
            // Put option: P = K * e^(-rT) * N(-d2) - S * N(-d1)
            return K * Math.exp(-r * T) * norm(-d2) - S * norm(-d1);
        }
    }

    /**
     * Vega: sensitivity of option price to changes in volatility.
     * Used in Newton-Raphson iteration to find sigma.
     *
     * @param S     spot price
     * @param K     strike price
     * @param T     time to expiry in years
     * @param r     risk-free rate
     * @param sigma volatility
     * @return vega value
     */
    private static double vega(double S, double K, double T, double r, double sigma) {
        double d1 = (Math.log(S / K) + (r + 0.5 * sigma * sigma) * T)
                / (sigma * Math.sqrt(T));
        return S * Math.sqrt(T) * normPdf(d1);
    }

    /**
     * Cumulative standard normal distribution function.
     * Uses error function approximation.
     *
     * @param x input value
     * @return cumulative probability
     */
    private static double norm(double x) {
        return 0.5 * (1 + erf(x / Math.sqrt(2)));
    }

    /**
     * Standard normal probability density function.
     *
     * @param x input value
     * @return probability density
     */
    private static double normPdf(double x) {
        return Math.exp(-0.5 * x * x) / Math.sqrt(2 * Math.PI);
    }

    /**
     * Error function approximation using Abramowitz and Stegun formula.
     * Accurate to within 1.5e-7.
     *
     * @param z input value
     * @return error function value
     */
    private static double erf(double z) {
        double t = 1.0 / (1.0 + 0.47047 * Math.abs(z));
        double poly = t * (0.3480242 + t * (-0.0958798 + t * 0.7478556));
        double result = 1 - poly * Math.exp(-z * z);
        return z >= 0 ? result : -result;
    }

    /**
     * Calculates option delta using Black-Scholes model.
     * Delta measures the rate of change of option price with respect to spot price.
     *
     * @param spotPrice    the current spot price of the underlying
     * @param strikePrice  the strike price of the option
     * @param timeToExpiry time to expiry in years (must be positive)
     * @param iv           implied volatility as a percentage (e.g., 18.45 for 18.45%)
     * @param optionType   "CE" for call option, "PE" for put option
     * @return delta value (typically 0 to 1 for calls, -1 to 0 for puts)
     */
    public static double delta(double spotPrice, double strikePrice,
                               double timeToExpiry, double iv,
                               String optionType) {
        if (timeToExpiry <= 0 || iv <= 0) return 0.0;
        double sigma = iv / 100.0;
        double d1 = (Math.log(spotPrice / strikePrice)
                + (RISK_FREE_RATE + 0.5 * sigma * sigma) * timeToExpiry)
                / (sigma * Math.sqrt(timeToExpiry));
        if ("CE".equals(optionType)) {
            return norm(d1);
        } else {
            return norm(d1) - 1.0;
        }
    }
}
