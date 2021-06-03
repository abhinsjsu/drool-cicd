# Positive  scenarios
def setdefault_conformant_case1():
    from collections import defaultdict
    dict = defaultdict(list)
    missing_key = [dict['A']]
    return missing_key

def setdefault_conformant_case2():
    from collections import defaultdict
    std_dict = {1: 1, 2: 2, 3: 3}
    def_dict = defaultdict(list, std_dict)
    def_dict[5].append("five")
    print(def_dict)

def setdefault_conformant_case3():
    from collections import defaultdict
    def_dict = defaultdict(list)
    for k, v in enumerate(range(5)):
        def_dict[k].append(v)
    return def_dict