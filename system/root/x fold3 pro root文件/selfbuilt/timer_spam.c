#define _GNU_SOURCE
#include <signal.h>
#include <time.h>
#include <stdio.h>
#include <unistd.h>
int main(void) {
    struct sigevent sev;
    for (int i = 0; i < 20000; i++) {
        sev.sigev_notify = SIGEV_NONE;
        sev.sigev_signo = 0;
        sev.sigev_value.sival_ptr = (void *)(unsigned long)i;
        timer_t t;
        if (timer_create(CLOCK_PROCESS_CPUTIME_ID, &sev, &t) == 0)
            timer_delete(t);
        if ((i & 0x3ff) == 0) usleep(1000);
    }
    return 0;
}
