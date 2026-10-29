# iCal4j 4.4 catches Android's absent ZoneRulesProvider and falls back to platform zones.
# joda-convert is an optional annotation in threeten-extra, not a runtime dependency.
-dontwarn java.time.zone.ZoneRulesProvider
-dontwarn org.joda.convert.ToString
