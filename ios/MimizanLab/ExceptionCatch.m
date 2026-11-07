#import "ExceptionCatch.h"

BOOL MimizanTry(void (^block)(void), NSError **error) {
    @try {
        block();
        return YES;
    } @catch (NSException *e) {
        if (error) {
            *error = [NSError errorWithDomain:@"ch.bojovic.mimizanlab" code:1
                                     userInfo:@{NSLocalizedDescriptionKey: e.reason ?: e.name}];
        }
        return NO;
    }
}
