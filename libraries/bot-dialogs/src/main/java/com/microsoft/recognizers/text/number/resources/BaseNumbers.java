// ------------------------------------------------------------------------------
// <auto-generated>
//     This code was generated by a tool.
//     Changes to this file may cause incorrect behavior and will be lost if
//     the code is regenerated.
// </auto-generated>
//
// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.
// ------------------------------------------------------------------------------

package com.microsoft.recognizers.text.number.resources;

import com.google.common.collect.ImmutableMap;

public class BaseNumbers {

    public static final String NumberReplaceToken = "@builtin.num";

    public static final String FractionNumberReplaceToken = "@builtin.num.fraction";

    public static String IntegerRegexDefinition(String placeholder, String thousandsmark) {
        return "(((?<!\\d+\\s*)-\\s*)|((?<=\\b)(?<!(\\d+\\.|\\d+,))))\\d{1,3}({thousandsmark}\\d{3})+(?={placeholder})"
            .replace("{placeholder}", placeholder)
            .replace("{thousandsmark}", thousandsmark);
    }

    public static final String FractionNotationRegex = "((((?<=\\W|^)-\\s*)|(?<![/-])(?<=\\b))\\d+[/]\\d+(?=(\\b[^/]|$))|[\\u00BC-\\u00BE\\u2150-\\u215E])";

    public static String DoubleRegexDefinition(String placeholder, String thousandsmark, String decimalmark) {
        return "(((?<!\\d+\\s*)-\\s*)|((?<=\\b)(?<!\\d+\\.|\\d+,)))\\d{1,3}({thousandsmark}\\d{3})+{decimalmark}\\d+(?={placeholder})"
            .replace("{placeholder}", placeholder)
            .replace("{thousandsmark}", thousandsmark)
            .replace("{decimalmark}", decimalmark);
    }

    public static final String PlaceHolderDefault = "\\D|\\b";

    public static final String CaseSensitiveTerms = "(?<=(\\s|\\d))(kB|K[Bb]?|M[BbM]?|G[Bb]?|B)\\b";

    public static final String NumberMultiplierRegex = "(K|k|MM?|mil|G|T|B|b)";

    public static final String MultiplierLookupRegex = "(k|m(il|m)?|t|g|b)";

    public static final String CurrencyRegex = "(((?<=\\W|^)-\\s*)|(?<=\\b))\\d+\\s*(b|m|t|g)(?=\\b)";

    public static final String CommonCurrencySymbol = "(¥|\\$|€|£|₩)";
}