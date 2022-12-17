package org.testcases.switches;

import java.math.RoundingMode;


public class ByEnum {
    public int enumSwitch(RoundingMode m) {
        switch (m) {
            case HALF_DOWN:
            case HALF_EVEN:
            case HALF_UP:
                return 1;
            case DOWN:
                return 2;
            case CEILING:
                return 3;
        }
        return -1;
    }
}
