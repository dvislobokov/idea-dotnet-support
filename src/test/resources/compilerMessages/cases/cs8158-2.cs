// docs: cs8158.md #2; codes: CS8158
    char Test1(char arg1, S1 arg2)
    {
        ref S1 r = ref arg2;
        return r.x;
    }
