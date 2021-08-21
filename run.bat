@echo off

java.exe -ea -Xms1024m -Xmx1024m -XX:+AlwaysPreTouch -XX:+UseG1GC -cp out jout test_out
