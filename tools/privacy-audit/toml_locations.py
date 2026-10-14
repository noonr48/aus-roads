"""CPython tomllib statement-end adapter, not a grammar or SDK policy.

Used only after public full-document parsing found references. A per-call clone
observes existing rules without changing module globals. Private ABI/position
drift raises at the caller's location seam; never silently use partial offsets.
"""
from types import FunctionType


def statement_ends(text):
    from tomllib import _parser

    names = ("key_value_rule", "create_dict_rule", "create_list_rule")
    parse = _parser.loads
    if not isinstance(parse, FunctionType) or not set(names).issubset(parse.__code__.co_names):
        raise RuntimeError("TOML location parser ABI unavailable")
    namespace = dict(parse.__globals__)
    ends = []

    def observe(rule):
        def completed(*args, **kwargs):
            result = rule(*args, **kwargs)
            end = result[0] if isinstance(result, tuple) else result
            if type(end) is not int or end <= (ends[-1] if ends else 0) or end > len(text):
                raise RuntimeError("TOML statement position invalid")
            ends.append(end)
            return result
        return completed

    for name in names:
        namespace[name] = observe(namespace[name])
    located = FunctionType(parse.__code__, namespace, argdefs=parse.__defaults__, closure=parse.__closure__)
    located.__kwdefaults__ = parse.__kwdefaults__
    located(text)
    if not ends:
        raise RuntimeError("TOML statement positions unresolved")
    return ends
