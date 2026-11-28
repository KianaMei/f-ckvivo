# Vivo Power Insight uses org.json directly (no reflection serialization).
# Keep Android Activity entry point cleanly.
-keep class dev.ganvivo.powerinsight.MainActivity { *; }
