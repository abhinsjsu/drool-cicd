#Should Flag
def raiseSameError():
    try:
        do_something()
    except KeyError:
        raise

#Should Flag
def raiseSameErrorWithVariableName():
    try:
        do_something()
    except KeyError as ex:
        raise ex

#Should Flag
def raiseSameError_MultipleErrors():
    try:
        do_something()
    except (KeyError, ValueError):
        raise

#Should not Flag
def hasOtherOperations_MultipleErrors():
    try:
        do_something()
    except (KeyError, ValueError):
        logging.exception('error while accessing the dict')
        raise

#Should not Flag
def hasOtherOperations():
    try:
        do_something()
    except KeyError as e:
        logging.exception('error while accessing the dict')
        raise e

#should flag
def nested():
    try:
        do_something()
    except KeyError as e:
        try:
            do_mode()
        except ValueError:
            raise
        raise e

#should not flag
def nested_confirming():
    try:
        do_something()
    except KeyError as e:
        try:
            do_mode()
        except ValueError:
            do_some_mode()
            raise
        raise e

#should not flag
#https://code.amazon.com/packages/CMS/blobs/8d9ea7e38971202b7817cdf9bf4ecffc2cb52850/--/tools/git/git-review.py#L808-L808
def multiple_exceptions(self, path, fields, files=None):
    try:
        r = urllib.request.Request(str(url), body, headers)
        data = urllib.request.urlopen(r).read()
        try:
            self.cookie_jar.save(self.cookie_file)
        except IOError as e:
            debug('Failed to write cookie file: %s' % e)
        return data
    except urllib.error.HTTPError as e:
        # Re-raise so callers can interpret it.
        raise e
    except urllib.error.URLError as e:
        try:
            debug(e.read())
        except AttributeError:
            pass

        die("Unable to access %s. The host path may be invalid\n%s" % \
            (url, e))

#should only flag inner except ValueError within except KeyError
def nested_multiple_exceptions():
    try:
        do_something()
    except KeyError as e:
        try:
            do_mode()
        except ValueError:
            raise
        raise e
    except ValueError as e:
        raise e
    except:
        logging.exception('error while accessing the dict')
        raise e
