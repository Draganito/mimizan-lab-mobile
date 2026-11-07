// AVFoundation raises NSException (not Swift Error) when a format or a
// stream type is refused. Those would kill the process; this turns them
// into an NSError the session can fall back from.
#import <Foundation/Foundation.h>

BOOL MimizanTry(void (^ _Nonnull block)(void), NSError * _Nullable * _Nullable error);
