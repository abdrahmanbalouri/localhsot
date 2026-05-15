#!/usr/bin/env python3
import time

# infinite loop — should trigger 504 Gateway Timeout after 10s
while True:
    time.sleep(1)
